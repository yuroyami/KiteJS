function Animal(name) { this.name = name; }
Animal.prototype.speak = function () { return this.name + ' makes a sound'; };
Animal.prototype.kind = 'animal';
function Dog(name) { Animal.call(this, name); }
Dog.prototype = Object.create(Animal.prototype);
Dog.prototype.constructor = Dog;
Dog.prototype.speak = function () { return Animal.prototype.speak.call(this) + ': woof'; };
var d = new Dog('Rex');
var base = { greet: function () { return 'hi ' + this.who; } };
var derived = Object.create(base, { who: { value: 'there', enumerable: true } });
var chain = [];
for (var o = d; o; o = Object.getPrototypeOf(o)) chain.push(o.constructor.name || '?');
var out = [
  d.speak(),
  d instanceof Dog, d instanceof Animal, d instanceof Object,
  d.hasOwnProperty('name'), d.hasOwnProperty('speak'), 'speak' in d, 'kind' in d,
  d.kind, Object.keys(d).join(), chain.join('>'),
  derived.greet(), Object.getPrototypeOf(derived) === base,
  Dog.prototype.isPrototypeOf(d), Animal.prototype.isPrototypeOf(d),
  Object.getOwnPropertyNames(Dog.prototype).sort().join()
];
d.kind = 'pet';
out.push(d.kind, Animal.prototype.kind, delete d.kind, d.kind);
out.join('|');
