import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { getPetSpritesheet, listPets, spritesheetEtag } from "./PetCatalog.js";

async function makePetsDir(): Promise<string> {
  return await mkdtemp(join(tmpdir(), "open-claw-pets-"));
}

async function writePet(
  root: string,
  id: string,
  options: { json?: unknown; spritesheet?: Buffer | null; sheetName?: string } = {}
): Promise<void> {
  const dir = join(root, id);
  await mkdir(dir, { recursive: true });
  if (options.json !== null) {
    const json = options.json ?? {
      id,
      displayName: id.charAt(0).toUpperCase() + id.slice(1),
      description: `${id} description`,
      spritesheetPath: "spritesheet.webp"
    };
    await writeFile(join(dir, "pet.json"), JSON.stringify(json));
  }
  if (options.spritesheet !== null) {
    const buffer = options.spritesheet ?? Buffer.from("RIFFFAKEWEBPDATA");
    await writeFile(join(dir, options.sheetName ?? "spritesheet.webp"), buffer);
  }
}

test("listPets returns valid pets sorted by display name", async () => {
  const root = await makePetsDir();
  try {
    await writePet(root, "zebra", { json: { id: "zebra", displayName: "Zebra", description: "z" } });
    await writePet(root, "alpha", { json: { id: "alpha", displayName: "Alpha", description: "a" } });
    const pets = await listPets(root);
    assert.equal(pets.length, 2);
    assert.deepEqual(pets.map((p) => p.id), ["alpha", "zebra"]);
    assert.equal(pets[0].displayName, "Alpha");
    assert.ok(pets[0].spritesheetSizeBytes > 0);
    assert.ok(pets[0].spritesheetMtimeMs > 0);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("listPets skips entries missing pet.json or spritesheet", async () => {
  const root = await makePetsDir();
  try {
    await writePet(root, "valid");
    await writePet(root, "no-json", { json: null as unknown });
    await writePet(root, "no-sheet", { spritesheet: null as unknown as Buffer });
    await writeFile(join(root, ".DS_Store"), Buffer.from([0]));
    const pets = await listPets(root);
    assert.equal(pets.length, 1);
    assert.equal(pets[0].id, "valid");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("listPets returns empty array when pets directory is missing", async () => {
  const root = await makePetsDir();
  await rm(root, { recursive: true, force: true });
  const pets = await listPets(root);
  assert.deepEqual(pets, []);
});

test("listPets tolerates malformed pet.json", async () => {
  const root = await makePetsDir();
  try {
    const dir = join(root, "broken");
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, "pet.json"), "not json");
    await writeFile(join(dir, "spritesheet.webp"), Buffer.from("RIFF"));
    const pets = await listPets(root);
    assert.deepEqual(pets, []);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("getPetSpritesheet returns metadata for valid pets and undefined otherwise", async () => {
  const root = await makePetsDir();
  try {
    await writePet(root, "valid");
    const info = await getPetSpritesheet("valid", root);
    assert.ok(info);
    assert.ok(info!.path.endsWith("spritesheet.webp"));
    assert.ok(info!.sizeBytes > 0);
    const etag = spritesheetEtag(info!);
    assert.match(etag, /^"[0-9a-f]+-[0-9a-f]+"$/);

    const missing = await getPetSpritesheet("does-not-exist", root);
    assert.equal(missing, undefined);

    const traversal = await getPetSpritesheet("../escape", root);
    assert.equal(traversal, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
