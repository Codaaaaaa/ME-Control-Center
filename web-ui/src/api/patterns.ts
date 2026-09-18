import { z } from 'zod';
import { userViewSchema } from './auth';
import { getJson, sendJson, sendNoContent } from './client';
import { locationSchema, resourceLabelSchema, type ResourceLabel } from './crafting';
import { resourcePageSchema, type ResourcePage, type Sort, type TypeFilter } from './resources';

/** Mirrors io.github.codaaaaaa.mecc.core.patterns.PatternViews (spec sections 13-17). */

export const PATTERN_TYPES = ['CRAFTING', 'PROCESSING', 'SMITHING', 'STONECUTTING'] as const;
export type PatternType = (typeof PATTERN_TYPES)[number];

/** Input slots per type; processing patterns grow as needed (up to the server's limit). */
export const FIXED_INPUTS: Record<PatternType, number | null> = {
  CRAFTING: 9,
  PROCESSING: null,
  SMITHING: 3,
  STONECUTTING: 1,
};

/** Recipe-driven types take items only, one per slot, and get their outputs from the game recipe. */
export function recipeDriven(type: PatternType): boolean {
  return type !== 'PROCESSING';
}

export const patternStackSchema = z.object({
  resource: resourceLabelSchema,
  amount: z.number(),
});
export type PatternStack = z.infer<typeof patternStackSchema>;

export const definitionSchema = z.object({
  type: z.enum(PATTERN_TYPES),
  inputs: z.array(patternStackSchema.nullable()),
  outputs: z.array(patternStackSchema.nullable()),
  substitutes: z.boolean(),
  fluidSubstitutes: z.boolean(),
  recipeId: z.string().nullable(),
});
export type Definition = z.infer<typeof definitionSchema>;

export const draftSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string(),
  networkId: z.string().nullable(),
  definition: definitionSchema,
  createdAt: z.string(),
  updatedAt: z.string(),
  assetVersion: z.string(),
});
export type Draft = z.infer<typeof draftSchema>;

export const draftListSchema = z.object({
  drafts: z.array(draftSchema),
  max: z.number(),
  assetVersion: z.string(),
});
export type DraftList = z.infer<typeof draftListSchema>;

export const issueSchema = z.object({
  code: z.string(),
  field: z.string().nullable(),
  message: z.string(),
});
export type PatternIssue = z.infer<typeof issueSchema>;

export const validationSchema = z.object({
  valid: z.boolean(),
  issues: z.array(issueSchema),
  outputs: z.array(patternStackSchema),
  recipeId: z.string().nullable(),
  blankPatterns: z.number().nullable(),
  assetVersion: z.string(),
});
export type Validation = z.infer<typeof validationSchema>;

export const storedPatternSchema = z.object({
  slot: z.number(),
  type: z.enum(PATTERN_TYPES).nullable(),
  outputs: z.array(patternStackSchema),
  inputs: z.array(patternStackSchema),
});
export type StoredPattern = z.infer<typeof storedPatternSchema>;

export const providerSchema = z.object({
  id: z.string(),
  name: z.string().nullable(),
  icon: resourceLabelSchema.nullable(),
  /** The container itself: Pattern Provider, ME Pattern Buffer, ... */
  kind: resourceLabelSchema.nullable(),
  /** The machine it supplies, e.g. the multiblock a pattern buffer belongs to. */
  machine: resourceLabelSchema.nullable(),
  customName: z.string().nullable(),
  renamable: z.boolean(),
  location: locationSchema.nullable(),
  online: z.boolean(),
  slots: z.number(),
  usedSlots: z.number(),
  priority: z.number().nullable(),
  blocking: z.boolean().nullable(),
  lockMode: z.string().nullable(),
  visibleInTerminal: z.boolean().nullable(),
  patterns: z.array(storedPatternSchema),
});
export type Provider = z.infer<typeof providerSchema>;

export const providerListSchema = z.object({
  capturedAt: z.string(),
  providers: z.array(providerSchema),
  blankPatterns: z.number(),
  canDeploy: z.boolean(),
  canConfigure: z.boolean(),
  assetVersion: z.string(),
});
export type ProviderList = z.infer<typeof providerListSchema>;

export const encodeResultSchema = z.object({
  deploymentId: z.string(),
  action: z.enum(['ENCODE', 'DEPLOY']),
  outputs: z.array(patternStackSchema),
  providerId: z.string().nullable(),
  providerName: z.string().nullable(),
  slot: z.number().nullable(),
  assetVersion: z.string(),
});
export type EncodeResult = z.infer<typeof encodeResultSchema>;

export const deploymentListSchema = z.object({
  deployments: z.array(
    z.object({
      id: z.string(),
      at: z.string(),
      actor: userViewSchema,
      action: z.enum(['ENCODE', 'DEPLOY']),
      type: z.enum(PATTERN_TYPES),
      output: resourceLabelSchema.nullable(),
      providerName: z.string().nullable(),
      slot: z.number().nullable(),
      errorCode: z.string().nullable(),
    }),
  ),
  assetVersion: z.string(),
});
export type DeploymentList = z.infer<typeof deploymentListSchema>;

export const recipeListSchema = z.object({
  recipes: z.array(
    z.object({
      id: z.string(),
      type: z.enum(PATTERN_TYPES),
      slots: z.array(z.object({ options: z.array(resourceLabelSchema), amount: z.number(), more: z.number() }).nullable()),
      output: patternStackSchema,
      byproducts: z.array(patternStackSchema),
      /** The machine or recipe type; null for crafting-table-like recipes. */
      category: z.object({ id: z.string(), name: z.string().nullable() }).nullable(),
      /** False when the machine may need more than listed (e.g. fluids): check before encoding. */
      complete: z.boolean(),
    }),
  ),
  truncated: z.boolean(),
  assetVersion: z.string(),
});
export type RecipeList = z.infer<typeof recipeListSchema>;
export type Recipe = RecipeList['recipes'][number];

// --- editor model -----------------------------------------------------------------------------------

/** A slot in the editor: a resource and its raw amount (millibuckets for fluids), or empty. */
export type Slot = PatternStack | null;

export interface Editor {
  type: PatternType;
  inputs: Slot[];
  outputs: Slot[];
  substitutes: boolean;
  fluidSubstitutes: boolean;
  recipeId: string | null;
}

export function emptyEditor(type: PatternType): Editor {
  return {
    type,
    inputs: Array.from({ length: FIXED_INPUTS[type] ?? 1 }, () => null),
    outputs: type === 'PROCESSING' ? [null] : [],
    substitutes: false,
    fluidSubstitutes: type === 'CRAFTING',
    recipeId: null,
  };
}

/** Brings a stored definition into editor shape: fixed-size types always get all their slots. */
export function editorFromDefinition(definition: Definition): Editor {
  const fixed = FIXED_INPUTS[definition.type];
  const inputs = fixed === null ? [...definition.inputs] : Array.from({ length: fixed }, (_, i) => definition.inputs[i] ?? null);
  if (inputs.length === 0) inputs.push(null);
  const outputs = definition.type === 'PROCESSING' ? [...definition.outputs] : [];
  if (definition.type === 'PROCESSING' && outputs.length === 0) outputs.push(null);
  return {
    type: definition.type,
    inputs,
    outputs,
    substitutes: definition.substitutes,
    fluidSubstitutes: definition.fluidSubstitutes,
    recipeId: definition.recipeId,
  };
}

/** Wire form of a slot: the resource by ID. */
export interface StackBody {
  resource: string;
  amount: number;
}

export interface DefinitionBody {
  type: PatternType;
  inputs: (StackBody | null)[];
  outputs: (StackBody | null)[];
  substitutes: boolean;
  fluidSubstitutes: boolean;
  recipeId: string | null;
}

export function definitionBody(editor: Editor): DefinitionBody {
  const body = (slot: Slot): StackBody | null => (slot ? { resource: slot.resource.id, amount: slot.amount } : null);
  return {
    type: editor.type,
    inputs: editor.inputs.map(body),
    outputs: recipeDriven(editor.type) ? [] : editor.outputs.map(body),
    substitutes: editor.substitutes,
    fluidSubstitutes: editor.type === 'CRAFTING' && editor.fluidSubstitutes,
    recipeId: editor.type === 'PROCESSING' ? null : editor.recipeId,
  };
}

/** Whether anything is in the editor at all (validation of an empty pattern only states the obvious). */
export function hasContent(editor: Editor): boolean {
  return editor.inputs.some((slot) => slot !== null) || editor.outputs.some((slot) => slot !== null);
}

/** Fills the editor from a game recipe, taking the first accepted resource of each slot. */
export function applyRecipe(editor: Editor, recipe: Recipe): Editor {
  const inputs = recipe.slots.map((slot) =>
    slot && slot.options[0] ? { resource: slot.options[0], amount: slot.amount } : null);
  if (recipe.type === 'PROCESSING') {
    // A machine recipe is a template: the pattern stays free-form, with the recipe's amounts and outputs.
    return {
      ...editor,
      type: 'PROCESSING',
      inputs: inputs.filter((slot) => slot !== null),
      outputs: [recipe.output, ...recipe.byproducts],
      recipeId: null,
    };
  }
  return { ...editor, type: recipe.type, inputs, outputs: [], recipeId: recipe.id };
}

// --- portable format (spec section 16) ---------------------------------------------------------------

export const PORTABLE_FORMAT = 'mecc-pattern';

const portableStackSchema = z.object({ resource: z.string().min(1), amount: z.number().int().positive() });

export const portablePatternSchema = z.object({
  format: z.literal(PORTABLE_FORMAT),
  version: z.literal(1),
  name: z.string().min(1).max(64),
  description: z.string().max(1000).optional().default(''),
  definition: z.object({
    type: z.enum(PATTERN_TYPES),
    inputs: z.array(portableStackSchema.nullable()).max(256),
    outputs: z.array(portableStackSchema.nullable()).max(256).optional().default([]),
    substitutes: z.boolean().optional().default(false),
    fluidSubstitutes: z.boolean().optional().default(false),
    recipeId: z.string().nullable().optional().default(null),
  }),
});
export type PortablePattern = z.infer<typeof portablePatternSchema>;

/** A `.mecc-pattern.json` document: version-independent resource IDs only, never game class names. */
export function toPortable(name: string, description: string, editor: Editor): PortablePattern {
  return { format: PORTABLE_FORMAT, version: 1, name, description, definition: definitionBody(editor) };
}

export function parsePortable(text: string): PortablePattern | null {
  try {
    const parsed = portablePatternSchema.safeParse(JSON.parse(text));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

export function portableFileName(name: string): string {
  const safe = name.trim().replace(/[^\p{L}\p{N}_-]+/gu, '_').replace(/^_+|_+$/g, '') || 'pattern';
  return `${safe}.mecc-pattern.json`;
}

// --- requests ----------------------------------------------------------------------------------------

const networkBase = (networkId: string) => `/api/v1/networks/${encodeURIComponent(networkId)}`;

export function fetchDrafts(locale: string, signal?: AbortSignal): Promise<DraftList> {
  return getJson(`/api/v1/patterns/drafts?locale=${encodeURIComponent(locale)}`, draftListSchema, signal);
}

export interface DraftInput {
  name: string;
  description: string;
  networkId: string | null;
  definition: DefinitionBody;
}

export function createDraft(input: DraftInput, locale: string): Promise<Draft> {
  return sendJson('POST', `/api/v1/patterns/drafts?locale=${encodeURIComponent(locale)}`, input, draftSchema);
}

export function updateDraft(id: string, input: DraftInput, locale: string): Promise<Draft> {
  return sendJson('PATCH', `/api/v1/patterns/drafts/${encodeURIComponent(id)}?locale=${encodeURIComponent(locale)}`, input,
    draftSchema);
}

export function deleteDraft(id: string): Promise<void> {
  return sendNoContent('DELETE', `/api/v1/patterns/drafts/${encodeURIComponent(id)}`);
}

export interface CatalogQuery {
  search: string;
  type: TypeFilter;
  sort: Sort;
  locale: string;
}

/** Every registered item and fluid, for resources the network does not have (yet). */
export function fetchCatalog(query: CatalogQuery, offset: number, limit: number, signal?: AbortSignal): Promise<ResourcePage> {
  const parameters = new URLSearchParams({
    q: query.search,
    type: query.type,
    sort: query.sort,
    offset: String(offset),
    limit: String(limit),
    locale: query.locale,
  });
  return getJson(`/api/v1/patterns/catalog?${parameters}`, resourcePageSchema, signal);
}

export function fetchRecipes(
  type: PatternType,
  by: { output: string } | { input: string },
  locale: string,
  signal?: AbortSignal,
): Promise<RecipeList> {
  const parameters = new URLSearchParams({ type, locale, ...by });
  return getJson(`/api/v1/patterns/recipes?${parameters}`, recipeListSchema, signal);
}

export function fetchProviders(networkId: string, locale: string, signal?: AbortSignal): Promise<ProviderList> {
  return getJson(`${networkBase(networkId)}/providers?locale=${encodeURIComponent(locale)}`, providerListSchema, signal);
}

/** Renames a provider or pattern buffer; an empty name removes the custom name. */
export function renameProvider(networkId: string, providerId: string, name: string): Promise<void> {
  return sendNoContent('PATCH', `${networkBase(networkId)}/providers/${encodeURIComponent(providerId)}`, { name });
}

/** AE2's lock-crafting modes (spec section 15). */
export const LOCK_MODES = ['NONE', 'LOCK_UNTIL_PULSE', 'LOCK_WHILE_HIGH', 'LOCK_WHILE_LOW', 'LOCK_UNTIL_RESULT'] as const;

export interface ProviderSettings {
  priority: number;
  blocking: boolean;
  lockMode: string;
  visibleInTerminal: boolean;
}

export function configureProvider(networkId: string, providerId: string, settings: ProviderSettings): Promise<void> {
  return sendNoContent('PATCH', `${networkBase(networkId)}/providers/${encodeURIComponent(providerId)}`, settings);
}

export interface DuplicateEntry {
  provider: Provider;
  pattern: StoredPattern;
}

/**
 * Patterns in different slots that make the same primary output (spec section 17). Not necessarily a mistake:
 * duplicates can be deliberate for parallel machines, so they are only shown, highest priority first.
 * `sameInputs` tells equivalent patterns from alternative recipes.
 */
export interface DuplicateGroup {
  output: PatternStack;
  entries: DuplicateEntry[];
  sameInputs: boolean;
}

export function findDuplicates(providers: readonly Provider[]): DuplicateGroup[] {
  const byOutput = new Map<string, DuplicateEntry[]>();
  for (const provider of providers) {
    for (const pattern of provider.patterns) {
      const output = pattern.outputs[0];
      if (!output) continue;
      const entries = byOutput.get(output.resource.id) ?? [];
      entries.push({ provider, pattern });
      byOutput.set(output.resource.id, entries);
    }
  }
  const inputsKey = (pattern: StoredPattern) =>
    pattern.inputs.map((input) => `${input.resource.id}*${input.amount}`).sort().join('|');
  return [...byOutput.values()]
    .filter((entries) => entries.length > 1)
    .map((entries) => ({
      output: entries[0]!.pattern.outputs[0]!,
      entries: [...entries].sort((a, b) => (b.provider.priority ?? 0) - (a.provider.priority ?? 0)),
      sameInputs: new Set(entries.map((entry) => inputsKey(entry.pattern))).size === 1,
    }))
    .sort((a, b) => a.output.resource.name.localeCompare(b.output.resource.name));
}

/** Text a provider list can be filtered by: its name, type, and machine. */
export function providerSearchText(provider: Provider): string {
  return [provider.name, provider.customName, provider.kind?.name, provider.machine?.name, provider.kind?.id,
    provider.machine?.id].filter(Boolean).join(' ').toLowerCase();
}

export function validatePattern(networkId: string, definition: DefinitionBody, locale: string, signal?: AbortSignal):
  Promise<Validation> {
  return sendJson('POST', `${networkBase(networkId)}/patterns/validate`, { definition, locale }, validationSchema, signal);
}

export function encodePattern(networkId: string, definition: DefinitionBody, draftId: string | null, locale: string):
  Promise<EncodeResult> {
  return sendJson('POST', `${networkBase(networkId)}/patterns/encode`, { definition, draftId, locale }, encodeResultSchema);
}

export function deployPattern(
  networkId: string,
  definition: DefinitionBody,
  draftId: string | null,
  providerId: string,
  locale: string,
): Promise<EncodeResult> {
  return sendJson('POST', `${networkBase(networkId)}/patterns/deploy`, { definition, draftId, providerId, locale },
    encodeResultSchema);
}

export function fetchDeployments(networkId: string, locale: string, signal?: AbortSignal): Promise<DeploymentList> {
  return getJson(`${networkBase(networkId)}/patterns/deployments?locale=${encodeURIComponent(locale)}`, deploymentListSchema,
    signal);
}

export type { ResourceLabel };
