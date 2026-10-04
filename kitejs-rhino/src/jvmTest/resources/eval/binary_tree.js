function TreeNode(v) { this.v = v; this.left = null; this.right = null; }
function BST() { this.root = null; this.count = 0; }
BST.prototype.insert = function (v) {
  var node = new TreeNode(v);
  this.count++;
  if (!this.root) { this.root = node; return this; }
  var cur = this.root;
  while (true) {
    if (v < cur.v) { if (!cur.left) { cur.left = node; break; } cur = cur.left; }
    else { if (!cur.right) { cur.right = node; break; } cur = cur.right; }
  }
  return this;
};
BST.prototype.inorder = function (node, out) {
  if (arguments.length === 0) { node = this.root; out = []; }
  if (node) { this.inorder(node.left, out); out.push(node.v); this.inorder(node.right, out); }
  return out;
};
BST.prototype.preorder = function (node = this.root, out = []) { if (node) { out.push(node.v); this.preorder(node.left, out); this.preorder(node.right, out); } return out; };
BST.prototype.height = function (node = this.root) { return node ? 1 + Math.max(this.height(node.left), this.height(node.right)) : 0; };
BST.prototype.contains = function (v) { var c = this.root; while (c) { if (v === c.v) return true; c = v < c.v ? c.left : c.right; } return false; };
BST.prototype.min = function () { var c = this.root; while (c.left) c = c.left; return c.v; };
BST.prototype.max = function () { var c = this.root; while (c.right) c = c.right; return c.v; };
BST.prototype.levels = function () {
  var out = [], q = this.root ? [this.root] : [];
  while (q.length) {
    var next = [], vals = [];
    q.forEach(function (n) { vals.push(n.v); if (n.left) next.push(n.left); if (n.right) next.push(n.right); });
    out.push(vals.join(','));
    q = next;
  }
  return out.join(' / ');
};
var t = new BST();
[50, 30, 70, 20, 40, 60, 80, 35, 45, 65].forEach(function (v) { t.insert(v); });
[t.inorder().join(), t.preorder().join(), t.height(), t.contains(45), t.contains(55), t.min(), t.max(), t.count, t.levels()].join('|');
