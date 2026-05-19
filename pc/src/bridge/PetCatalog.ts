import { readdir, readFile, stat } from "node:fs/promises";
import { createReadStream, type Stats } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

export interface PetSummary {
  id: string;
  displayName: string;
  description: string;
  spritesheetSizeBytes: number;
  spritesheetMtimeMs: number;
}

export interface PetSpritesheetInfo {
  path: string;
  sizeBytes: number;
  mtimeMs: number;
}

interface PetJson {
  id?: string;
  displayName?: string;
  description?: string;
  spritesheetPath?: string;
}

/**
 * Resolve the Codex pets directory. Honors `CODEX_HOME` env var, otherwise
 * falls back to `~/.codex`. The pets live in `<codex-home>/pets/<pet-id>/`.
 */
export function resolvePetsDir(): string {
  const codexHome = process.env.CODEX_HOME?.trim();
  const base = codexHome && codexHome.length > 0 ? codexHome : join(homedir(), ".codex");
  return join(base, "pets");
}

async function safeStat(path: string): Promise<Stats | undefined> {
  try {
    return await stat(path);
  } catch {
    return undefined;
  }
}

async function readPetJson(path: string): Promise<PetJson | undefined> {
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === "object") {
      return parsed as PetJson;
    }
  } catch {
    /* ignore malformed pet.json */
  }
  return undefined;
}

function sanitizeSpritesheetPath(petDir: string, raw: string | undefined): string {
  const value = typeof raw === "string" && raw.trim().length > 0 ? raw.trim() : "spritesheet.webp";
  const resolved = resolve(petDir, value);
  if (!resolved.startsWith(petDir)) {
    // Refuse to escape the pet directory; fall back to default location.
    return join(petDir, "spritesheet.webp");
  }
  return resolved;
}

/**
 * Scan the pets directory and return a summary entry for each valid pet
 * (one with both `pet.json` and a spritesheet file). Tolerates missing or
 * malformed pets by skipping them.
 */
export async function listPets(petsDir: string = resolvePetsDir()): Promise<PetSummary[]> {
  let entries: string[];
  try {
    entries = await readdir(petsDir);
  } catch {
    return [];
  }

  const pets: PetSummary[] = [];
  for (const entry of entries) {
    if (entry.startsWith(".")) {
      continue;
    }
    const petDir = join(petsDir, entry);
    const dirStat = await safeStat(petDir);
    if (!dirStat?.isDirectory()) {
      continue;
    }
    const petJsonPath = join(petDir, "pet.json");
    const petJson = await readPetJson(petJsonPath);
    if (!petJson) {
      continue;
    }
    const spritesheetPath = sanitizeSpritesheetPath(petDir, petJson.spritesheetPath);
    const sheetStat = await safeStat(spritesheetPath);
    if (!sheetStat?.isFile()) {
      continue;
    }
    const id = typeof petJson.id === "string" && petJson.id.trim().length > 0
      ? petJson.id.trim()
      : entry;
    const displayName = typeof petJson.displayName === "string" && petJson.displayName.trim().length > 0
      ? petJson.displayName.trim()
      : id;
    const description = typeof petJson.description === "string" ? petJson.description.trim() : "";
    pets.push({
      id,
      displayName,
      description,
      spritesheetSizeBytes: sheetStat.size,
      spritesheetMtimeMs: Math.floor(sheetStat.mtimeMs)
    });
  }

  pets.sort((a, b) => a.displayName.localeCompare(b.displayName));
  return pets;
}

/**
 * Resolve metadata for a pet's spritesheet, if the pet exists and has a
 * valid spritesheet file on disk. Returns undefined otherwise.
 */
export async function getPetSpritesheet(
  petId: string,
  petsDir: string = resolvePetsDir()
): Promise<PetSpritesheetInfo | undefined> {
  if (!isSafePetId(petId)) {
    return undefined;
  }
  const petDir = join(petsDir, petId);
  const dirStat = await safeStat(petDir);
  if (!dirStat?.isDirectory()) {
    return undefined;
  }
  const petJson = await readPetJson(join(petDir, "pet.json"));
  if (!petJson) {
    return undefined;
  }
  const spritesheetPath = sanitizeSpritesheetPath(petDir, petJson.spritesheetPath);
  const sheetStat = await safeStat(spritesheetPath);
  if (!sheetStat?.isFile()) {
    return undefined;
  }
  return {
    path: spritesheetPath,
    sizeBytes: sheetStat.size,
    mtimeMs: Math.floor(sheetStat.mtimeMs)
  };
}

export function openSpritesheetStream(info: PetSpritesheetInfo): NodeJS.ReadableStream {
  return createReadStream(info.path);
}

export function spritesheetEtag(info: PetSpritesheetInfo): string {
  return `"${info.sizeBytes.toString(16)}-${info.mtimeMs.toString(16)}"`;
}

function isSafePetId(value: string): boolean {
  if (!value || value.length === 0 || value.length > 128) return false;
  return /^[A-Za-z0-9._-]+$/.test(value) && value !== "." && value !== "..";
}
