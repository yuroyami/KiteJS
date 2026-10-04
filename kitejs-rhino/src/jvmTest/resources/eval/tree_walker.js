// An in-order walk written as a generator, plus the same walk done eagerly for comparison.
function Node(value, left, right) {
  this.value = value;
  this.left = left || null;
  this.right = right || null;
}
function insert(node, value) {
  if (node === null) return new Node(value);
  if (value < node.value) node.left = insert(node.left, value);
  else if (value > node.value) node.right = insert(node.right, value);
  return node;
}
var root = null;
var input = [50, 30, 70, 20, 40, 60, 80, 35, 45, 75];
for (var i = 0; i < input.length; i++) root = insert(root, input[i]);

function* inOrder(node) {
  if (node === null) return;
  yield* inOrder(node.left);
  yield node.value;
  yield* inOrder(node.right);
}
function* preOrder(node) {
  if (node === null) return;
  yield node.value;
  yield* preOrder(node.left);
  yield* preOrder(node.right);
}
function* leaves(node) {
  if (node === null) return;
  if (node.left === null && node.right === null) { yield node.value; return; }
  yield* leaves(node.left);
  yield* leaves(node.right);
}

var sortedOut = [...inOrder(root)].join();
var pre = [...preOrder(root)].join();
var leafList = [...leaves(root)].join();

// The generator is lazy: stop as soon as the answer is known.
function firstAbove(node, limit) {
  for (var v of inOrder(node)) if (v > limit) return v;
  return null;
}
var above = firstAbove(root, 44);

// Depth without a generator, to check the tree itself.
function depth(node) {
  if (node === null) return 0;
  var l = depth(node.left);
  var r = depth(node.right);
  return 1 + (l > r ? l : r);
}

// A generator can be restarted only by calling the function again.
var it = inOrder(root);
var firstThree = [it.next().value, it.next().value, it.next().value].join();
it.return(0);
var afterReturn = it.next().done;

[sortedOut, pre, leafList, above, depth(root), firstThree, afterReturn].join('|');
