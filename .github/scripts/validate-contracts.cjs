// Validates event/WebSocket contracts under contracts/:
//   - every *.schema.json must be a valid JSON Schema (draft 2020-12) and compile;
//   - every <Name>.example.json must validate against <Name>.schema.json.
// Requires `ajv` and `ajv-formats` resolvable via NODE_PATH.
const { readFileSync, readdirSync } = require('node:fs');
const { join, basename } = require('node:path');
const Ajv2020 = require('ajv/dist/2020');
const addFormats = require('ajv-formats');

const root = process.argv[2] || 'contracts';
const files = readdirSync(root, { recursive: true })
  .map(String)
  .map((f) => join(root, f).replaceAll('\\', '/'));
const schemas = files.filter((f) => f.endsWith('.schema.json'));
const examples = files.filter((f) => f.endsWith('.example.json'));

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

let failed = false;
const fail = (file, message) => {
  console.error(`::error file=${file}::${message}`);
  failed = true;
};

const keyByName = new Map();
for (const file of schemas) {
  try {
    const schema = JSON.parse(readFileSync(file, 'utf8'));
    const key = schema.$id || file;
    ajv.addSchema(schema, key);
    keyByName.set(basename(file, '.schema.json'), { key, file });
  } catch (e) {
    fail(file, e.message);
  }
}

// Compile after all schemas are registered so cross-file $refs resolve.
for (const { key, file } of keyByName.values()) {
  try {
    ajv.getSchema(key);
  } catch (e) {
    fail(file, e.message);
  }
}

for (const file of examples) {
  const entry = keyByName.get(basename(file, '.example.json'));
  if (!entry) {
    fail(file, 'No matching <Name>.schema.json found for this example');
    continue;
  }
  try {
    const validate = ajv.getSchema(entry.key);
    if (validate && !validate(JSON.parse(readFileSync(file, 'utf8')))) {
      fail(file, ajv.errorsText(validate.errors));
    }
  } catch (e) {
    fail(file, e.message);
  }
}

console.log(`Checked ${schemas.length} schema(s) and ${examples.length} example(s).`);
process.exit(failed ? 1 : 0);
