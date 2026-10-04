function Node(value, next) { this.value = value; this.next = next || null; }
function LinkedList() { this.head = null; this.size = 0; }
LinkedList.prototype.push = function (v) {
  var node = new Node(v);
  if (!this.head) this.head = node;
  else { var cur = this.head; while (cur.next) cur = cur.next; cur.next = node; }
  this.size++;
  return this;
};
LinkedList.prototype.toArray = function () { var out = []; for (var c = this.head; c; c = c.next) out.push(c.value); return out; };
LinkedList.prototype.reverse = function () { var prev = null, cur = this.head; while (cur) { var next = cur.next; cur.next = prev; prev = cur; cur = next; } this.head = prev; return this; };
LinkedList.prototype.remove = function (v) { if (!this.head) return false; if (this.head.value === v) { this.head = this.head.next; this.size--; return true; } for (var c = this.head; c.next; c = c.next) if (c.next.value === v) { c.next = c.next.next; this.size--; return true; } return false; };
LinkedList.prototype.find = function (pred) { for (var c = this.head; c; c = c.next) if (pred(c.value)) return c.value; return undefined; };
LinkedList.prototype.map = function (f) { var l = new LinkedList(); for (var c = this.head; c; c = c.next) l.push(f(c.value)); return l; };
var l = new LinkedList().push(1).push(2).push(3).push(4);
var out = [l.toArray().join(), l.size, l.reverse().toArray().join(), l.remove(3), l.remove(99), l.toArray().join(), l.size, l.find(function (v) { return v % 2 === 0; }), l.map(function (v) { return v * 10; }).toArray().join()];
var middle = (function (list) { var slow = list.head, fast = list.head; while (fast && fast.next) { slow = slow.next; fast = fast.next.next; } return slow.value; })(new LinkedList().push('a').push('b').push('c').push('d').push('e'));
out.push(middle);
out.join('|');
