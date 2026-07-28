// Serializes rows to RFC-4180 CSV. A field is quoted only when it contains a
// comma, quote, or newline; embedded quotes are doubled. null/undefined render
// as an empty field. CRLF line endings keep Excel happy.
export function toCsv(rows: (string | number | null | undefined)[][]): string {
  const escape = (value: string | number | null | undefined): string => {
    if (value === null || value === undefined) return "";
    const s = String(value);
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  return rows.map((row) => row.map(escape).join(",")).join("\r\n");
}

// Minimal RFC-4180-ish CSV parser: quoted fields, escaped quotes (""),
// CRLF/LF line endings. Returns rows as arrays of strings.
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (inQuotes) {
      if (char === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += char;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      field = "";
      if (row.length > 1 || row[0] !== "") rows.push(row);
      row = [];
    } else {
      field += char;
    }
  }
  row.push(field);
  if (row.length > 1 || row[0] !== "") rows.push(row);
  return rows;
}
