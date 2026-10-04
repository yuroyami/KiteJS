var out = [];
var obj = {
  name: 'obj',
  regular: function () { return this === undefined ? 'undefined' : this === globalThis ? 'global' : this.name; },
  arrow: () => (typeof this === 'undefined' ? 'lexical-undefined' : this === globalThis ? 'lexical-global' : 'lexical-' + this.name),
  nested: function () {
    var inner = function () { return this === globalThis ? 'inner-global' : 'inner-' + (this && this.name); };
    var arrow = () => 'arrow-' + this.name;
    return inner() + ',' + arrow() + ',' + inner.call({ name: 'x' });
  },
  strict: function () { 'use strict'; return this === undefined ? 'strict-undefined' : 'strict-' + this.name; }
};
var extracted = obj.regular;
var strictExtracted = obj.strict;
out.push(obj.regular(), extracted(), obj.arrow(), obj.nested(), obj.strict(), strictExtracted());
out.push(obj.regular.call({ name: 'called' }), obj.regular.apply({ name: 'applied' }, []), obj.regular.bind({ name: 'bound' })());
var bound = obj.regular.bind({ name: 'first' });
out.push(bound.call({ name: 'second' }), bound.bind({ name: 'third' })(), typeof new bound());
function Ctor() { this.made = true; return this.made; }
out.push(new Ctor().made, Ctor.call({}), obj.regular.call(5) === 'global' ? 'wrapped-global' : typeof obj.regular.call(5));
var proto = { who: function () { return this.tag; } };
var child = Object.create(proto); child.tag = 'child';
out.push(child.who(), proto.who.call({ tag: 'other' }));
var arr = [function () { return this.length; }];
out.push(arr[0]());
var s = { v: 1, inc: function () { this.v++; return this; } };
out.push(s.inc().inc().inc().v);
out.join('|');
