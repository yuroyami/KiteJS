function Stack() { this.items = []; }
Stack.prototype.push = function (x) { this.items.push(x); return this; };
Stack.prototype.pop = function () { return this.items.pop(); };
Stack.prototype.peek = function () { return this.items[this.items.length - 1]; };
Stack.prototype.isEmpty = function () { return this.items.length === 0; };
function Queue() { this.items = []; }
Queue.prototype.enqueue = function (x) { this.items.push(x); return this; };
Queue.prototype.dequeue = function () { return this.items.shift(); };
function balanced(s) {
  var st = new Stack(), pairs = { ')': '(', ']': '[', '}': '{' };
  for (var i = 0; i < s.length; i++) {
    var c = s[i];
    if ('([{'.indexOf(c) >= 0) st.push(c);
    else if (c in pairs) { if (st.pop() !== pairs[c]) return false; }
  }
  return st.isEmpty();
}
function rpn(expr) {
  var st = new Stack();
  expr.split(' ').forEach(function (tok) {
    if (!isNaN(tok)) st.push(Number(tok));
    else { var b = st.pop(), a = st.pop(); st.push(tok === '+' ? a + b : tok === '-' ? a - b : tok === '*' ? a * b : a / b); }
  });
  return st.pop();
}
var q = new Queue().enqueue('a').enqueue('b').enqueue('c');
var order = [q.dequeue(), q.dequeue(), q.enqueue('d').dequeue(), q.dequeue(), q.dequeue()].join();
[balanced('([]{})'), balanced('([)]'), balanced('(('), balanced(''), rpn('3 4 + 2 *'), rpn('5 1 2 + 4 * + 3 -'), order, new Stack().push(1).push(2).peek()].join('|');
