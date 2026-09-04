var calls = [];
var src = { a: 1, get g() { calls.push('g'); return 'getter'; }, set s(v) {} };
Object.defineProperty(src, 'hidden', { value: 'h', enumerable: false });
var proto = { inherited: 'i' };
var withProto = Object.create(proto);
withProto.own = 'o';
var target = Object.assign({ start: 1 }, src, null, undefined, { a: 2, b: 3 }, withProto, 'ab', 5, true, [9, 8]);
var spread = { x: 1, ...src, ...withProto, ...null, ...'cd', ...[7], x: 'over' };
var out = [JSON.stringify(target), Object.keys(target).join(), JSON.stringify(spread), Object.keys(spread).join(), calls.join(), typeof target.s];
var frozenTarget = Object.freeze({ a: 1 });
try { Object.assign(frozenTarget, { a: 2 }); out.push('no throw'); } catch (e) { out.push(e.name); }
var withSetter = { set v(x) { calls.push('set:' + x); } };
Object.assign(withSetter, { v: 5 });
out.push(calls.join(), withSetter.v, Object.getOwnPropertyDescriptor({ ...src }, 'g').value, Object.getOwnPropertyDescriptor(Object.assign({}, src), 'g').writable);
var nestedCopy = { ...{ n: { deep: 1 } } };
var original = { n: { deep: 1 } }, copy = { ...original };
copy.n.deep = 2;
out.push(original.n.deep, JSON.stringify({ ...[1, 2] }), JSON.stringify({ ...{ length: 0 } }), JSON.stringify(Object.assign([1, 2, 3], [4])), Object.assign({}, { toString: 'x' }).toString);
out.join('|');
