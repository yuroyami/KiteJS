// A small template engine: {{name}}, {{#each}}...{{/each}} and {{!comment}}.
function render(tpl, data) {
  return tpl
    .replace(/\{\{!.*?\}\}/g, '')
    .replace(/\{\{#each (\w+)\}\}([\s\S]*?)\{\{\/each\}\}/g, function (all, key, body) {
      var list = data[key] || [];
      var out = '';
      for (var i = 0; i < list.length; i++) {
        out += body.replace(/\{\{this\}\}/g, String(list[i])).replace(/\{\{@index\}\}/g, String(i));
      }
      return out;
    })
    .replace(/\{\{(\w+(?:\.\w+)*)\}\}/g, function (all, path) {
      var parts = path.split('.'), value = data;
      for (var i = 0; i < parts.length; i++) {
        if (value === undefined || value === null) return '';
        value = value[parts[i]];
      }
      return value === undefined ? '' : String(value);
    });
}
var data = { title: 'Report', user: { name: 'Ann' }, items: ['a', 'b', 'c'], missing: undefined };
var tpl = '{{!ignored}}<h1>{{title}}</h1> by {{user.name}} ({{user.email}}) {{#each items}}[{{@index}}:{{this}}]{{/each}} {{nope}}!';
var out = render(tpl, data);

// Escaping with a replacement string rather than a function.
var escaped = '<a href="x">&</a>'.replace(/[<>&"]/g, function (c) {
  return { '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c];
});

// $-patterns in a plain replacement string.
var swapped = 'first last'.replace(/(\w+) (\w+)/, '$2, $1');
var doubled = 'abc'.replace(/b/, '$&$&');
var around = 'abc'.replace(/b/, "[$`|$']");

[out, escaped, swapped, doubled, around].join('|');
