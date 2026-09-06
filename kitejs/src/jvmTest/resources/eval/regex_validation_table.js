// Runs a table of inputs through a table of patterns, so every cell is compared.
var PATTERNS = {
  email: /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/,
  hex: /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/,
  ipv4: /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/,
  isoDate: /^\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])$/,
  slug: /^[a-z0-9]+(?:-[a-z0-9]+)*$/,
  time24: /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/,
  identifier: /^[A-Za-z_$][A-Za-z0-9_$]*$/,
  quoted: /^"(?:[^"\\]|\\.)*"$/
};
var INPUTS = [
  'a@b.co', 'a@b', '@b.co', 'a b@c.de',
  '#fff', '#ffffff', '#ff', '#gggggg',
  '0.0.0.0', '255.255.255.255', '256.1.1.1', '1.2.3',
  '2024-02-29', '2024-13-01', '2024-1-1',
  'hello-world', 'Hello-World', 'a--b', 'a-b-c',
  '00:00', '23:59:59', '24:00', '9:00',
  '_x1', '1x', '$a', 'a-b',
  '"ok"', '"esc\\""', '"bad', 'plain'
];
var rows = [];
var names = Object.keys(PATTERNS).sort();
for (var i = 0; i < names.length; i++) {
  var re = PATTERNS[names[i]];
  var marks = '';
  for (var j = 0; j < INPUTS.length; j++) marks += re.test(INPUTS[j]) ? '1' : '0';
  rows.push(names[i] + ':' + marks);
}

// The same patterns used to extract rather than to test.
var found = 'mail me at a@b.co or c.d@e.fr today'.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g);

// Anchors and multiline over a block of text.
var block = 'one\ntwo\nthree';
var starts = block.match(/^\w+/gm);
var ends = block.match(/\w+$/gm);

rows.join('|') + '#' + found.join() + '#' + starts.join() + '#' + ends.join();
