package io.github.codaaaaaa.mecc.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.codaaaaaa.mecc.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noMinecraftLoaderOrAe2 = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "net.minecraftforge..", "net.neoforged..", "com.mojang..", "appeng..");

    @ArchTest
    static final ArchRule platformApiDoesNotKnowUpperLayers = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.codaaaaaa.mecc.runtime..",
                    "io.github.codaaaaaa.mecc.web..",
                    "io.github.codaaaaaa.mecc.forge..");
}
