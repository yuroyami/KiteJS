// A CSV reader that handles quoted fields, embedded commas, quotes and newlines.
function parseCSV(text) {
  var rows = [], row = [], field = '', i = 0, inQuotes = false;
  while (i < text.length) {
    var c = text.charAt(i);
    if (inQuotes) {
      if (c === '"') {
        if (text.charAt(i + 1) === '"') { field += '"'; i += 2; continue; }
        inQuotes = false; i++; continue;
      }
      field += c; i++; continue;
    }
    if (c === '"') { inQuotes = true; i++; continue; }
    if (c === ',') { row.push(field); field = ''; i++; continue; }
    if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; i++; continue; }
    field += c; i++;
  }
  row.push(field);
  rows.push(row);
  return rows;
}
var text = 'name,age,note\n"Doe, Jane",31,"said ""hi"""\nBob,25,plain\n"multi\nline",1,x';
var rows = parseCSV(text);

// The same job done with a single regex, to compare on the simple rows.
var simpleRe = /(?:^|,)(?:"([^"]*)"|([^",]*))/g;
function parseSimpleRow(line) {
  var out = [], m;
  simpleRe.lastIndex = 0;
  while ((m = simpleRe.exec(line)) !== null) {
    out.push(m[1] !== undefined ? m[1] : m[2]);
    if (simpleRe.lastIndex === line.length) break;
  }
  return out;
}
var simple = parseSimpleRow('a,b,"c,d",e');

// Splitting on a pattern that keeps its delimiter.
var kept = 'a1b22c333d'.split(/(\d+)/);

[rows.length, rows[1].join('|'), rows[3].join('|'), simple.join('|'), kept.join('~'), JSON.stringify(rows[0])].join('#');
