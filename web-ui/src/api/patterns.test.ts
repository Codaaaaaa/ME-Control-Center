import { describe, expect, it } from 'vitest';
import type { ResourceLabel } from './crafting';
import { liveEffects } from './live';
import {
  applyRecipe,
  definitionBody,
  editorFromDefinition,
  emptyEditor,
  findDuplicates,
  hasContent,
  parsePortable,
  portableFileName,
  toPortable,
  type Provider,
  type Recipe,
} from './patterns';

const label = (id: string, unit: ResourceLabel['unit'] = null): ResourceLabel => ({
  id,
  type: id.split(':')[0] ?? 'item',
  name: id,
  nameSpans: null,
  modId: 'minecraft',
  modName: 'Minecraft',
  unit,
  iconKey: id.replace(/:/g, '/'),
});

const plank = label('item:minecraft:oak_planks');
const water = label('fluid:minecraft:water', { symbol: 'B', amountPerUnit: 1000 });

describe('pattern editor model', () => {
  it('gives every fixed-size type all of its slots', () => {
    expect(emptyEditor('CRAFTING').inputs).toHaveLength(9);
    expect(emptyEditor('SMITHING').inputs).toHaveLength(3);
    expect(emptyEditor('STONECUTTING').inputs).toHaveLength(1);
    expect(emptyEditor('PROCESSING')).toMatchObject({ inputs: [null], outputs: [null] });
    expect(hasContent(emptyEditor('CRAFTING'))).toBe(false);
  });

  it('sends resources by ID and drops what the type does not use', () => {
    const editor = { ...emptyEditor('CRAFTING'), outputs: [{ resource: plank, amount: 4 }], recipeId: 'minecraft:stick' };
    editor.inputs[0] = { resource: plank, amount: 1 };
    const body = definitionBody(editor);
    expect(body.inputs[0]).toEqual({ resource: 'item:minecraft:oak_planks', amount: 1 });
    expect(body.inputs[1]).toBeNull();
    expect(body.outputs).toEqual([]);
    expect(body.recipeId).toBe('minecraft:stick');

    const processing = { ...emptyEditor('PROCESSING'), inputs: [{ resource: water, amount: 250 }], recipeId: 'x:y' };
    expect(definitionBody(processing)).toMatchObject({ recipeId: null, fluidSubstitutes: false });
  });

  it('restores stored definitions into editor shape', () => {
    const editor = editorFromDefinition({
      type: 'CRAFTING',
      inputs: [{ resource: plank, amount: 1 }],
      outputs: [],
      substitutes: true,
      fluidSubstitutes: false,
      recipeId: null,
    });
    expect(editor.inputs).toHaveLength(9);
    expect(editor.inputs[0]?.resource.id).toBe(plank.id);
    expect(editor.substitutes).toBe(true);
  });

  it('fills inputs from a recipe with the first accepted item per slot', () => {
    const recipe: Recipe = {
      id: 'minecraft:stick',
      type: 'CRAFTING',
      slots: [{ options: [plank, label('item:minecraft:birch_planks')], amount: 1, more: 3 }, null, null, { options: [plank], amount: 1, more: 0 },
        null, null, null, null, null],
      output: { resource: label('item:minecraft:stick'), amount: 4 },
      byproducts: [],
      category: null,
      complete: true,
    };
    const filled = applyRecipe(emptyEditor('CRAFTING'), recipe);
    expect(filled.recipeId).toBe('minecraft:stick');
    expect(filled.inputs.map((slot) => slot?.resource.id ?? null)).toEqual([plank.id, null, null, plank.id, null, null, null,
      null, null]);
  });

  it('turns a machine recipe into a processing pattern with its amounts and outputs', () => {
    const recipe: Recipe = {
      id: 'gtceu:assembler/wireless_energy_hatch_hv',
      type: 'PROCESSING',
      slots: [{ options: [plank], amount: 4, more: 0 }, { options: [water], amount: 144, more: 0 }],
      output: { resource: label('item:gtmthings:hv_wireless_energy_input_hatch'), amount: 1 },
      byproducts: [{ resource: label('item:minecraft:stick'), amount: 2 }],
      category: { id: 'gtceu:assembler', name: 'Assembler' },
      complete: true,
    };
    const filled = applyRecipe(emptyEditor('CRAFTING'), recipe);
    expect(filled.type).toBe('PROCESSING');
    expect(filled.recipeId).toBeNull();
    expect(filled.inputs.map((slot) => [slot?.resource.id, slot?.amount])).toEqual([[plank.id, 4], [water.id, 144]]);
    expect(filled.outputs.map((slot) => slot?.resource.id)).toEqual(['item:gtmthings:hv_wireless_energy_input_hatch',
      'item:minecraft:stick']);
  });
});

describe('portable pattern format', () => {
  it('round-trips through .mecc-pattern.json', () => {
    const editor = { ...emptyEditor('PROCESSING'), inputs: [{ resource: water, amount: 250 }, null],
      outputs: [{ resource: plank, amount: 2 }] };
    const text = JSON.stringify(toPortable('Wet planks', 'demo', editor));
    const parsed = parsePortable(text);
    expect(parsed?.name).toBe('Wet planks');
    expect(parsed?.definition.inputs).toEqual([{ resource: 'fluid:minecraft:water', amount: 250 }, null]);
    expect(parsed?.definition.outputs).toEqual([{ resource: 'item:minecraft:oak_planks', amount: 2 }]);
  });

  it('rejects files that are not ME Control Center patterns', () => {
    expect(parsePortable('not json')).toBeNull();
    expect(parsePortable(JSON.stringify({ format: 'other', version: 1 }))).toBeNull();
    expect(parsePortable(JSON.stringify({ format: 'mecc-pattern', version: 1, name: 'x',
      definition: { type: 'CRAFTING', inputs: [{ resource: 'item:minecraft:stone', amount: 0 }] } }))).toBeNull();
  });

  it('makes safe file names', () => {
    expect(portableFileName('Quantum / Processor: v2')).toBe('Quantum_Processor_v2.mecc-pattern.json');
    expect(portableFileName('钛锭')).toBe('钛锭.mecc-pattern.json');
    expect(portableFileName('   ')).toBe('pattern.mecc-pattern.json');
  });
});

describe('live pattern events', () => {
  it('turns pattern.deployed into a provider refresh', () => {
    expect(liveEffects({ type: 'pattern.deployed', timestamp: '', networkId: 'n1', payload: {} }))
      .toEqual([{ kind: 'patterns', networkId: 'n1' }]);
  });
});

describe('duplicate pattern analysis', () => {
  const iron = label('item:minecraft:iron_ingot');
  const ore = label('item:minecraft:raw_iron');
  const provider = (id: string, priority: number | null, patterns: Provider['patterns']): Provider => ({
    id, name: id, icon: null, kind: null, machine: null, customName: null, renamable: true, location: null, online: true,
    slots: 9, usedSlots: patterns.length, priority, blocking: false, lockMode: 'NONE', visibleInTerminal: true, patterns,
  });
  const smelt = (slot: number, inputAmount: number) => ({
    slot, type: 'PROCESSING' as const, outputs: [{ resource: iron, amount: 1 }], inputs: [{ resource: ore, amount: inputAmount }],
  });

  it('groups patterns with the same primary output, highest priority first', () => {
    const groups = findDuplicates([
      provider('low', -100, [smelt(0, 1)]),
      provider('high', 100, [smelt(3, 1)]),
      provider('other', 0, [{ slot: 0, type: 'CRAFTING', outputs: [{ resource: plank, amount: 4 }], inputs: [] }]),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.output.resource.id).toBe(iron.id);
    expect(groups[0]!.entries.map((entry) => entry.provider.id)).toEqual(['high', 'low']);
    expect(groups[0]!.sameInputs).toBe(true);
  });

  it('tells alternative recipes from equivalent patterns', () => {
    const groups = findDuplicates([provider('a', 0, [smelt(0, 1), smelt(1, 2)])]);
    expect(groups[0]!.sameInputs).toBe(false);
  });
});
