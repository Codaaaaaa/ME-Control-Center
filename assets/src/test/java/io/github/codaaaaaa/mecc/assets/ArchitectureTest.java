package io.github.codaaaaaa.mecc.assets;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.codaaaaaa.mecc.assets", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noMinecraftLoaderOrAe2 = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("net.minecraft..", "net.minecraftforge..", "net.neoforged..", "com.mojang..", "appeng..");

    /** Assets are pure data processing on top of core-domain. */
    @ArchTest
    static final ArchRule assetsKnowOnlyCore = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.codaaaaaa.mecc.platform..",
                    "io.github.codaaaaaa.mecc.runtime..",
                    "io.github.codaaaaaa.mecc.web..",
                    "io.github.codaaaaaa.mecc.persistence..",
                    "io.github.codaaaaaa.mecc.forge..");
}
