package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Fails the build on a module-boundary violation: a class that uses another module's {@code web}
 * or {@code data} package, or a module its own module does not allow.
 *
 * From Story 2.2, {@code materials.service.ScopeReads} is used only by {@code materials} and {@code scope} (AD-9).
 *
 * <p>The fixtures (source set {@code boundaryFixtures}) prove that the same verification really
 * fails on each kind of violation, and passes on a tree without one.
 */
class ModuleBoundaryTest {

    private static final String FIXTURES = "io.github.gmshieldfixtures.";

    @Test
    void applicationModulesRespectTheirBoundaries() {
        ApplicationModules modules = ApplicationModules.of(GmshieldApplication.class);

        modules.verify();
        assertThat(modules.getModuleByName("shared")).isPresent();
    }

    @Test
    void everyApplicationModuleNamesTheModulesItMayUse() {
        assertThat(modulesWithoutDeclaredDependencies(ApplicationModules.of(GmshieldApplication.class))).isEmpty();
    }

    @Test
    void aModuleThatLeavesItsDependenciesOpenIsFound() {
        // In the fixture, alpha has @ApplicationModule without allowedDependencies; beta declares them.
        assertThat(modulesWithoutDeclaredDependencies(ApplicationModules.of(FIXTURES + "valid")))
                .containsExactly("alpha");
        // gamma has no @ApplicationModule at all.
        assertThat(modulesWithoutDeclaredDependencies(ApplicationModules.of(FIXTURES + "undeclared")))
                .containsExactly("gamma");
    }

    /**
     * Modules whose package has no {@code @ApplicationModule} or leaves {@code allowedDependencies}
     * at its default, which lets the module use every other module's {@code service}.
     */
    private static List<String> modulesWithoutDeclaredDependencies(ApplicationModules modules) {
        List<String> open = new ArrayList<>();
        for (org.springframework.modulith.core.ApplicationModule module : modules) {
            org.springframework.modulith.ApplicationModule declared = null;
            try {
                declared = Class.forName(module.getBasePackage().getName() + ".package-info")
                        .getAnnotation(org.springframework.modulith.ApplicationModule.class);
            } catch (ClassNotFoundException noPackageInfo) {
                // No package-info class: nothing is declared.
            }
            if (declared == null || Arrays.asList(declared.allowedDependencies())
                    .contains(org.springframework.modulith.ApplicationModule.OPEN_TOKEN)) {
                open.add(module.getIdentifier().toString());
            }
        }
        return open;
    }

    @Test
    void everyClassOfAModuleIsInWebServiceOrData() {
        String base = GmshieldApplication.class.getPackageName();
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(base)) {
            if (type.getPackageName().equals(base) || type.getSimpleName().equals("package-info")) {
                continue;
            }
            assertThat(type.getPackageName())
                    .as("%s must be in <module>.web, <module>.service or <module>.data", type.getName())
                    .matches(java.util.regex.Pattern.quote(base) + "\\.[a-z][a-z0-9]*\\.(web|service|data)(\\..+)?");
        }
    }

    /**
     * AD-19: only {@code vault} encrypts, signs, hashes, computes a MAC or makes keys. Any other class
     * that uses {@code javax.crypto}, {@code java.security.spec}, {@code MessageDigest}, {@code Signature},
     * {@code KeyPairGenerator}, {@code KeyFactory}, {@code KeyStore}, Bouncy Castle, Tink or Spring
     * Security's crypto fails the build.
     */
    static ArchRule cryptoOnlyInVault(String vaultPackage) {
        return noClasses().that().resideOutsideOfPackage(vaultPackage + "..")
                .should().dependOnClassesThat(resideInAnyPackage("javax.crypto..", "java.security.spec..",
                                "org.bouncycastle..", "com.google.crypto.tink..", "org.springframework.security.crypto..",
                                // hashing helpers: a digest of content outside the vault would bypass its keyed fingerprints
                                "com.google.common.hash..", "org.apache.commons.codec.digest..")
                        .or(belongToAnyOf(MessageDigest.class, Signature.class, javax.crypto.Mac.class,
                                java.security.KeyPairGenerator.class, java.security.KeyFactory.class,
                                java.security.KeyStore.class, org.springframework.util.DigestUtils.class)))
                .because("every cipher, MAC, digest and signature belongs to the vault module (AD-19)");
    }

    @Test
    void onlyTheVaultUsesACipherADigestAMacOrASignature() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(GmshieldApplication.class.getPackageName());

        assertThat(application.contain(io.github.gmshield.vault.service.Vault.class)).isTrue();
        cryptoOnlyInVault(GmshieldApplication.class.getPackageName() + ".vault").check(application);
    }

    @Test
    void aCipherOutsideTheVaultFails() {
        JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + "cipher");

        assertThatThrownBy(() -> cryptoOnlyInVault(FIXTURES + "cipher.vault").check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("HomemadeHash").hasMessageContaining("HomemadeMac")
                .hasMessageContaining("HomemadeSignature").hasMessageContaining("HomemadeCipher")
                .hasMessageContaining("HomemadeKeyPair").hasMessageContaining("HomemadeSpringDigest")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("AllowedCipher").doesNotContain("PlainNotes"));
        JavaClasses allowed = new ClassFileImporter().importClasses(
                io.github.gmshieldfixtures.cipher.vault.service.AllowedCipher.class,
                io.github.gmshieldfixtures.cipher.notes.service.PlainNotes.class);
        assertThatCode(() -> cryptoOnlyInVault(FIXTURES + "cipher.vault").check(allowed)).doesNotThrowAnyException();
    }

    /**
     * AD-9 (Story 2.2): {@code scope} is the one resolver of what a campaign may use. Outside {@code materials}, only
     * {@code scope} may depend on {@code materials.service.ScopeReads} (or a type nested in it), the one public way to
     * read library items for a campaign; any other class that does fails the build.
     */
    static ArchRule scopeReadsOnlyFromMaterialsAndScope(String base) {
        return classes().that().haveNameMatching(java.util.regex.Pattern.quote(base + ".materials.service.ScopeReads")
                        + "(\\$.+)?")
                .should().onlyHaveDependentClassesThat().resideInAnyPackage(base + ".materials..", base + ".scope..")
                .because("only the scope module reads library items for a campaign (AD-9)");
    }

    @Test
    void onlyMaterialsAndScopeUseScopeReads() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(GmshieldApplication.class.getPackageName());

        assertThat(application.contain(io.github.gmshield.materials.service.ScopeReads.class)).isTrue();
        scopeReadsOnlyFromMaterialsAndScope(GmshieldApplication.class.getPackageName()).check(application);
    }

    @Test
    void anotherModuleThatUsesScopeReadsFails() {
        JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES + "scopereads");

        assertThatThrownBy(() -> scopeReadsOnlyFromMaterialsAndScope(FIXTURES + "scopereads").check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DirectRead")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ScopeUse").doesNotContain("MaterialsUse"));
        JavaClasses allowed = new ClassFileImporter().importClasses(
                io.github.gmshieldfixtures.scopereads.materials.service.ScopeReads.class,
                io.github.gmshieldfixtures.scopereads.materials.service.MaterialsUse.class,
                io.github.gmshieldfixtures.scopereads.scope.service.ScopeUse.class);
        assertThatCode(() -> scopeReadsOnlyFromMaterialsAndScope(FIXTURES + "scopereads").check(allowed))
                .doesNotThrowAnyException();
    }

    /**
     * Story 3.1 part B: a system's deletion reaches {@code materials} and {@code conversations} only through the
     * {@code library.service.SystemDeletionHandler} each implements, so {@code library} still depends on no feature
     * module, and both handlers are there. Story 3.2 part B: a campaign's deletion goes through the same handlers.
     */
    @Test
    void theLibraryDeletesThroughHandlersAndUsesNoFeatureModule() {
        String base = GmshieldApplication.class.getPackageName();
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(base);

        noClasses().that().resideInAPackage(base + ".library..").should().dependOnClassesThat()
                .resideInAnyPackage(base + ".materials..", base + ".conversations..", base + ".jobs..",
                        base + ".scope..", base + ".accounts..", base + ".audit..", base + ".limits..")
                .because("library depends on shared and vault only").check(application);
        List<String> handlers = application.get(io.github.gmshield.library.service.SystemDeletionHandler.class)
                .getAllSubclasses().stream().map(JavaClass::getPackageName).sorted().toList();
        assertThat(handlers).containsExactly(base + ".conversations.service", base + ".materials.service");
    }

    @Test
    void fixtureWithoutAViolationPasses() {
        assertThatCode(() -> ApplicationModules.of(FIXTURES + "valid").verify()).doesNotThrowAnyException();
    }

    @Test
    void usingAnotherModulesDataPackageFails() {
        assertThatThrownBy(() -> ApplicationModules.of(FIXTURES + "usesdata").verify())
                .isInstanceOf(Violations.class).hasMessageContaining("AlphaRow");
    }

    @Test
    void usingAnotherModulesWebPackageFails() {
        assertThatThrownBy(() -> ApplicationModules.of(FIXTURES + "usesweb").verify())
                .isInstanceOf(Violations.class).hasMessageContaining("AlphaEndpoint");
    }

    @Test
    void usingAModuleThatIsNotAllowedFails() {
        assertThatThrownBy(() -> ApplicationModules.of(FIXTURES + "notallowed").verify())
                .isInstanceOf(Violations.class).hasMessageContaining("gamma");
    }
}
