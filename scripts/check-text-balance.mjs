import fs from "node:fs";

const files = [
  "supabase/functions/ops-glasses/index.ts",
  "supabase/functions/ops-glasses/automigrate.ts",
];

for (const file of files) {
  const text = fs.readFileSync(file, "utf8");
  checkBalance(file, text);
}

console.log("Text balance check passed.");

function checkBalance(file, text) {
  const stack = [];
  let quote = "";
  let escaped = false;

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];

    if (quote) {
      if (escaped) {
        escaped = false;
        continue;
      }
      if (char === "\\") {
        escaped = true;
        continue;
      }
      if (char === quote) {
        quote = "";
      }
      continue;
    }

    if (char === "\"" || char === "'" || char === "`") {
      quote = char;
      continue;
    }

    if (char === "(" || char === "{" || char === "[") {
      stack.push({ char, index });
      continue;
    }

    if (char === ")" || char === "}" || char === "]") {
      const open = stack.pop();
      if (!open || !matches(open.char, char)) {
        throw new Error(`${file}: unbalanced ${char} at ${index}`);
      }
    }
  }

  if (quote) {
    throw new Error(`${file}: unterminated string ${quote}`);
  }
  if (stack.length) {
    const open = stack.at(-1);
    throw new Error(`${file}: unclosed ${open.char} at ${open.index}`);
  }
}

function matches(open, close) {
  return (open === "(" && close === ")") ||
    (open === "{" && close === "}") ||
    (open === "[" && close === "]");
}
