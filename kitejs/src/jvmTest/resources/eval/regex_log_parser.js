// Parses a small access log and rolls it up.
var LINE = /^(?<ip>\d+\.\d+\.\d+\.\d+) - (?<user>\S+) \[(?<when>[^\]]+)\] "(?<method>[A-Z]+) (?<path>\S+) HTTP\/(?<ver>[\d.]+)" (?<status>\d{3}) (?<bytes>\d+|-)$/;
var lines = [
  '10.0.0.1 - alice [2024-01-02T03:04:05] "GET /index.html HTTP/1.1" 200 1234',
  '10.0.0.2 - - [2024-01-02T03:04:06] "POST /api/items HTTP/1.1" 201 87',
  'garbage line',
  '10.0.0.1 - alice [2024-01-02T03:04:07] "GET /missing HTTP/1.0" 404 -',
  '10.0.0.3 - bob [2024-01-02T03:04:08] "DELETE /api/items/7 HTTP/1.1" 500 0'
];
var parsed = [], bad = 0;
for (var i = 0; i < lines.length; i++) {
  var m = LINE.exec(lines[i]);
  if (m === null) { bad++; continue; }
  parsed.push(m.groups);
}
var byStatus = {};
for (var j = 0; j < parsed.length; j++) {
  var s = parsed[j].status.charAt(0) + 'xx';
  byStatus[s] = (byStatus[s] || 0) + 1;
}
var totalBytes = parsed.reduce(function (sum, r) { return sum + (r.bytes === '-' ? 0 : Number(r.bytes)); }, 0);
var ips = parsed.map(function (r) { return r.ip; }).filter(function (v, i, a) { return a.indexOf(v) === i; });
var paths = parsed.map(function (r) { return r.path; });

// Redacting the addresses with a replacement callback.
var redacted = lines[0].replace(/\d+\.\d+\.\d+\.\d+/, function (ip) { return ip.split('.').slice(0, 2).join('.') + '.x.x'; });

// A case-insensitive scan for a word anywhere in the log.
var hits = lines.filter(function (l) { return /alice/i.test(l); }).length;

[parsed.length, bad, JSON.stringify(byStatus), totalBytes, ips.join(), paths.join(), redacted, hits].join('|');
