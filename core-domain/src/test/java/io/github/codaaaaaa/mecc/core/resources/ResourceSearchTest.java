package io.github.codaaaaaa.mecc.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ResourceSearchTest {

    private record TestRow(String name, String englishName, String id, String modId, String modName, List<String> tags,
                           String type, boolean craftable, long amount) implements ResourceSearch.Row {
    }

    private static final TestRow IRON = new TestRow("iron ingot", "iron ingot", "item:minecraft:iron_ingot",
            "minecraft", "minecraft", List.of("forge:ingots/iron", "minecraft:beacon_payment_items"), "item", true, 2_400);
    private static final TestRow OSMIUM = new TestRow("锇锭", "osmium ingot", "item:mekanism:ingot_osmium",
            "mekanism", "mekanism", List.of("forge:ingots/osmium"), "item", false, 64);
    private static final TestRow WATER = new TestRow("water", "water", "fluid:minecraft:water",
            "minecraft", "minecraft", List.of(), "fluid", false, 16_000);

    private static boolean matches(String query, ResourceSearch.Row row) {
        Predicate<ResourceSearch.Row> predicate = ResourceSearch.parse(query);
        return predicate.test(row);
    }

    @Test
    void matchesNamesIdsAndIsCaseInsensitive() {
        assertTrue(matches("iron", IRON));
        assertTrue(matches("IRON INGOT", IRON));
        assertFalse(matches("iron", OSMIUM));
        assertTrue(matches("minecraft:iron_ingot", IRON));
        assertTrue(matches("", OSMIUM), "an empty query matches everything");
    }

    @Test
    void matchesLocalizedAndEnglishNames() {
        assertTrue(matches("锇", OSMIUM), "current locale name");
        assertTrue(matches("osmium", OSMIUM), "English name still works in another locale");
    }

    @Test
    void supportsModTagTypeCraftableAndAmountFilters() {
        assertTrue(matches("@mekanism", OSMIUM));
        assertFalse(matches("@mekanism", IRON));
        assertTrue(matches("#forge:ingots", IRON));
        assertFalse(matches("#forge:ingots", WATER));
        assertTrue(matches("craftable:true", IRON));
        assertFalse(matches("craftable:true", OSMIUM));
        assertTrue(matches("type:fluid", WATER));
        assertFalse(matches("type:fluid", IRON));
        assertTrue(matches("amount:<1000", OSMIUM));
        assertFalse(matches("amount:<1000", IRON));
        assertTrue(matches("amount:>1k", IRON));
        assertTrue(matches("amount:>=64", OSMIUM));
        assertTrue(matches("amount:64", OSMIUM));
    }

    @Test
    void combinesTermsWithAndAndSupportsQuotedPhrases() {
        assertTrue(matches("@minecraft iron craftable:true", IRON));
        assertFalse(matches("@minecraft iron craftable:false", IRON));
        assertTrue(matches("\"iron ingot\"", IRON));
        assertFalse(matches("\"ingot iron\"", IRON));
    }

    @Test
    void ignoresMalformedFiltersRatherThanFailing() {
        assertFalse(matches("amount:<abc", IRON), "unparsable amount falls back to a text match");
        assertEquals(List.of("a", "b c"), ResourceSearch.tokenize("a \"b c\""));
    }

    @Test
    void parsesResourceIds() {
        ResourceId plain = ResourceId.parse("item:minecraft:iron_ingot").orElseThrow();
        assertEquals("minecraft:iron_ingot", plain.registryId());
        assertEquals("item:minecraft:iron_ingot", plain.toString());

        ResourceId variant = ResourceId.parse("item:minecraft:enchanted_book:3f9a0c1d2e4b").orElseThrow();
        assertEquals("3f9a0c1d2e4b", variant.variant());
        assertEquals(plain.type(), variant.base().type());
        assertEquals("item:minecraft:enchanted_book", variant.base().toString());

        assertTrue(ResourceId.parse("item:minecraft").isEmpty());
        assertTrue(ResourceId.parse("item:Minecraft:Iron").isEmpty(), "registry ids are lowercase");
        assertTrue(ResourceId.parse("item:minecraft:iron:NOTHEX").isEmpty());
        assertTrue(ResourceId.parse(null).isEmpty());
        assertEquals("item:gtceu:tools/wrench", ResourceId.parse("item:gtceu:tools/wrench").orElseThrow().toString());
    }
}
