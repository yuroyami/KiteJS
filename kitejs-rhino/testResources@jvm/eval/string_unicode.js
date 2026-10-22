var s = 'a😀b';
var out = [s.length, s.codePointAt(1), s.codePointAt(2), s.charCodeAt(1), s.split('').length, Array.from(s).length];
var count = 0;
for (var ch of s) count++;
out.push(count, String.fromCodePoint(0x1F600) === '😀', String.fromCodePoint(0x1F600).length, 'é'.toUpperCase(), 'straße'.toUpperCase(), 'I'.toLowerCase());
out.push(escape('éሴ'), unescape('%E9%u1234').length, encodeURIComponent('é 😀'), decodeURIComponent('%C3%A9'), encodeURI('http://x/y z?q=é#f'));
out.push('\x41B\u{43}', '\u{1F600}'.length, 'tab\there'.length, 'a\
b'.length);
out.push(s.isWellFormed(), '\uD800x'.isWellFormed(), '\uD800x'.toWellFormed().charCodeAt(0), JSON.stringify('\uD800'), JSON.stringify(s));
out.push('abc' < 'abd', 'a' < 'B', 'Z' < 'a', 'é' > 'z', ''.localeCompare(''), 'a'.localeCompare('a'));
out.join('|');
