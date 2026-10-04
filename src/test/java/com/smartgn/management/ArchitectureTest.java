package com.smartgn.management;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void domainDoesNotDependOnFrameworksOrInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.smartgn.management");
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "..configuration..", "..adapter..", "..integration..", "..application..", "tools.jackson..", "com.fasterxml.jackson..")
                .check(classes);
        noClasses().that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..", "..configuration..", "..adapter..")
                .check(classes);
    }
}
