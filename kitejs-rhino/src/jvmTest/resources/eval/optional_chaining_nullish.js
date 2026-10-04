var data = { user: { name: 'k', tags: ['a'], greet: function () { return 'hi ' + this.name; }, zero: 0, empty: '', f: false }, list: null };
var out = [
  data.user?.name, data.missing?.name, data.list?.[0], data.user?.tags?.[0], data.user?.tags?.[5], data.user.greet?.(), data.user.nope?.(), data?.user?.['name'],
  data.user.zero ?? 'default', data.user.empty ?? 'default', data.user.f ?? 'default', data.user.undef ?? 'default', data.list ?? 'default', null ?? undefined ?? 'last',
  data.user.zero || 'fallback', data.user.empty || 'fallback', (data.user.zero ?? 5) + 1, data.missing?.deep.deeper.deepest, typeof data.missing?.(),
  (null)?.x, (undefined)?.[1], data.user?.tags.length, data.user.greet?.call({ name: 'other' })
];
var count = 0;
function side() { count++; return { v: 1 }; }
var r = null?.[side()];
out.push(count, r, side()?.v, count);
out.push(typeof data.user?.greet, delete data.user?.zero, 'zero' in data.user);
out.join('|');
