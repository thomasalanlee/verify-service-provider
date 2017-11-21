package uk.gov.ida.verifyserviceprovider.factories.saml;

import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.PredicateRoleDescriptorResolver;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.security.impl.MetadataCredentialResolver;
import org.opensaml.xmlsec.config.DefaultSecurityConfigurationBootstrap;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;
import uk.gov.ida.saml.deserializers.OpenSamlXMLObjectUnmarshaller;
import uk.gov.ida.saml.deserializers.StringToOpenSamlObjectTransformer;
import uk.gov.ida.saml.deserializers.parser.SamlObjectParser;
import uk.gov.ida.saml.deserializers.validators.Base64StringDecoder;
import uk.gov.ida.saml.deserializers.validators.NotNullSamlStringValidator;
import uk.gov.ida.saml.security.AssertionDecrypter;
import uk.gov.ida.saml.security.CredentialFactorySignatureValidator;
import uk.gov.ida.saml.security.DecrypterFactory;
import uk.gov.ida.saml.security.IdaKeyStore;
import uk.gov.ida.saml.security.IdaKeyStoreCredentialRetriever;
import uk.gov.ida.saml.security.MetadataBackedSignatureValidator;
import uk.gov.ida.saml.security.SamlAssertionsSignatureValidator;
import uk.gov.ida.saml.security.SamlMessageSignatureValidator;
import uk.gov.ida.saml.security.SignatureValidator;
import uk.gov.ida.saml.security.SigningCredentialFactory;
import uk.gov.ida.saml.security.SigningKeyStore;
import uk.gov.ida.saml.security.validators.encryptedelementtype.EncryptionAlgorithmValidator;
import uk.gov.ida.saml.security.validators.signature.SamlResponseSignatureValidator;
import uk.gov.ida.verifyserviceprovider.configuration.MsaConfiguration;
import uk.gov.ida.verifyserviceprovider.services.AssertionTranslator;
import uk.gov.ida.verifyserviceprovider.services.ResponseService;
import uk.gov.ida.verifyserviceprovider.utils.DateTimeComparator;
import uk.gov.ida.verifyserviceprovider.validators.AssertionValidator;
import uk.gov.ida.verifyserviceprovider.validators.AudienceRestrictionValidator;
import uk.gov.ida.verifyserviceprovider.validators.ConditionsValidator;
import uk.gov.ida.verifyserviceprovider.validators.InstantValidator;
import uk.gov.ida.verifyserviceprovider.validators.ResponseSizeValidator;
import uk.gov.ida.verifyserviceprovider.validators.SubjectValidator;
import uk.gov.ida.verifyserviceprovider.validators.TimeRestrictionValidator;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static uk.gov.ida.verifyserviceprovider.utils.Crypto.publicKeyFromPrivateKey;

public class ResponseFactory {

    private static final NotNullSamlStringValidator notNullSamlStringValidator = new NotNullSamlStringValidator();
    private static final Base64StringDecoder base64StringDecoder = new Base64StringDecoder();
    private static final ResponseSizeValidator responseSizeValidator = new ResponseSizeValidator();
    private static final SamlObjectParser samlObjectParser = new SamlObjectParser();
    private static final OpenSamlXMLObjectUnmarshaller<Response> responseOpenSamlXMLObjectUnmarshaller = new OpenSamlXMLObjectUnmarshaller<>(samlObjectParser);
    private static final EncryptionAlgorithmValidator encryptionAlgorithmValidator = new EncryptionAlgorithmValidator();
    private static final DecrypterFactory decrypterFactory = new DecrypterFactory();

    private final PrivateKey samlPrimaryEncryptionKey;
    private final PrivateKey samlSecondaryEncryptionKey;

    public ResponseFactory(
        PrivateKey samlPrimaryEncryptionKey,
        PrivateKey samlSecondaryEncryptionKey
    ) {
        this.samlPrimaryEncryptionKey = samlPrimaryEncryptionKey;
        this.samlSecondaryEncryptionKey = samlSecondaryEncryptionKey;
    }

    public static StringToOpenSamlObjectTransformer<Response> createStringToResponseTransformer() {
        return new StringToOpenSamlObjectTransformer<>(
            notNullSamlStringValidator,
            base64StringDecoder,
            responseSizeValidator,
            responseOpenSamlXMLObjectUnmarshaller
        );
    }

    public AssertionDecrypter createAssertionDecrypter() {
        return new AssertionDecrypter(
            new IdaKeyStoreCredentialRetriever(createEncryptionKeyStore()),
            encryptionAlgorithmValidator,
            decrypterFactory
        );
    }

    public ResponseService createResponseService(
        MetadataResolver hubMetadataResolver,
        AssertionTranslator assertionTranslator,
        DateTimeComparator dateTimeComparator
    ) throws ComponentInitializationException {
        AssertionDecrypter assertionDecrypter = createAssertionDecrypter();
        MetadataBackedSignatureValidator metadataBackedSignatureValidator = getMetadataBackedSignatureValidator(hubMetadataResolver);

        return new ResponseService(
            createStringToResponseTransformer(),
            assertionDecrypter,
            assertionTranslator,
            new SamlResponseSignatureValidator(new SamlMessageSignatureValidator(metadataBackedSignatureValidator)),
            new InstantValidator(dateTimeComparator)
        );
    }

    public AssertionTranslator createAssertionTranslator(
        MetadataResolver msaMetadataResolver,
        MsaConfiguration msaConfiguration,
        DateTimeComparator dateTimeComparator
    ) throws ComponentInitializationException {
        // TODO: We should have configuration backed signature validator
//        MetadataBackedSignatureValidator metadataBackedSignatureValidator = getMetadataBackedSignatureValidator(msaMetadataResolver);
//        SamlMessageSignatureValidator samlMessageSignatureValidator = new SamlMessageSignatureValidator(metadataBackedSignatureValidator);
        SignatureValidator signatureValidator = getCredentialFactorySignatureValidator(msaConfiguration);
        SamlMessageSignatureValidator samlMessageSignatureValidator = new SamlMessageSignatureValidator(signatureValidator);
        TimeRestrictionValidator timeRestrictionValidator = new TimeRestrictionValidator(dateTimeComparator);

        SamlAssertionsSignatureValidator assertionsSignatureValidator = new SamlAssertionsSignatureValidator(samlMessageSignatureValidator);
        AssertionValidator assertionValidator = new AssertionValidator(
            new InstantValidator(dateTimeComparator),
            new SubjectValidator(timeRestrictionValidator),
            new ConditionsValidator(timeRestrictionValidator, new AudienceRestrictionValidator())
        );

        return new AssertionTranslator(
            assertionsSignatureValidator,
            assertionValidator
        );
    }

    private CredentialFactorySignatureValidator getCredentialFactorySignatureValidator(MsaConfiguration msaConfiguration) {
        SigningKeyStore signingKeyStore = new SigningKeyStore() {
            @Override
            public List<PublicKey> getVerifyingKeysForEntity(String entityId) {
                return new ArrayList<PublicKey>() {{
                    add(msaConfiguration.getPrimarySigningCertificate().getPublicKey());
                    Certificate secondarySigningCertificate = msaConfiguration.getSecondarySigningCertificate();

                    if (secondarySigningCertificate != null){
                        add(secondarySigningCertificate.getPublicKey());
                    }
                }};
            }
        };
        SigningCredentialFactory credentialFactory = new SigningCredentialFactory(signingKeyStore);
        return new CredentialFactorySignatureValidator(credentialFactory);
    }

    private MetadataBackedSignatureValidator getMetadataBackedSignatureValidator(MetadataResolver metadataResolver) throws ComponentInitializationException {
        MetadataCredentialResolver metadataCredentialResolver = getMetadataCredentialResolver(metadataResolver);
        ExplicitKeySignatureTrustEngine explicitKeySignatureTrustEngine = new ExplicitKeySignatureTrustEngine(
            metadataCredentialResolver,
            DefaultSecurityConfigurationBootstrap.buildBasicInlineKeyInfoCredentialResolver()
        );
        return MetadataBackedSignatureValidator.withoutCertificateChainValidation(explicitKeySignatureTrustEngine);
    }

    private MetadataCredentialResolver getMetadataCredentialResolver(MetadataResolver metadataResolver) throws ComponentInitializationException {
        PredicateRoleDescriptorResolver predicateRoleDescriptorResolver = new PredicateRoleDescriptorResolver(metadataResolver);
        predicateRoleDescriptorResolver.initialize();
        MetadataCredentialResolver metadataCredentialResolver = new MetadataCredentialResolver();
        metadataCredentialResolver.setRoleDescriptorResolver(predicateRoleDescriptorResolver);
        metadataCredentialResolver.setKeyInfoCredentialResolver(DefaultSecurityConfigurationBootstrap.buildBasicInlineKeyInfoCredentialResolver());
        metadataCredentialResolver.initialize();
        return metadataCredentialResolver;
    }

    private IdaKeyStore createEncryptionKeyStore() {
        KeyPair primaryEncryptionKeyPair = new KeyPair(publicKeyFromPrivateKey(samlPrimaryEncryptionKey), samlPrimaryEncryptionKey);
        List<KeyPair> encryptionKeyPairs;
        if (samlSecondaryEncryptionKey == null) {
            encryptionKeyPairs = singletonList(primaryEncryptionKeyPair);
        } else {
            encryptionKeyPairs = asList(primaryEncryptionKeyPair, new KeyPair(publicKeyFromPrivateKey(samlSecondaryEncryptionKey), samlSecondaryEncryptionKey));
        }
        return new IdaKeyStore(null, encryptionKeyPairs);
    }
}
