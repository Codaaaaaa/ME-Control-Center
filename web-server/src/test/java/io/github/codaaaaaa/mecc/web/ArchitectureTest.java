package io.github.codaaaaaa.mecc.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.codaaaaaa.mecc.web", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noMinecraftLoaderOrAe2 = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "net.minecraftforge..", "net.neoforged..", "com.mojang..", "appeng..");

    /** The web layer talks to core-domain service ports only, never to the platform layer directly. */
    @ArchTest
    static final ArchRule webDoesNotKnowPlatformOrRuntime = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.codaaaaaa.mecc.platform..",
                    "io.github.codaaaaaa.mecc.runtime..",
                    "io.github.codaaaaaa.mecc.forge..");
}
