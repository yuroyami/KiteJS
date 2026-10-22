function make(rows, cols, f) { var m = []; for (var i = 0; i < rows; i++) { m.push([]); for (var j = 0; j < cols; j++) m[i].push(f(i, j)); } return m; }
function transpose(m) { return m[0].map(function (_, j) { return m.map(function (row) { return row[j]; }); }); }
function multiply(a, b) { return a.map(function (row) { return transpose(b).map(function (col) { return row.reduce(function (s, v, k) { return s + v * col[k]; }, 0); }); }); }
function spiral(m) {
  var out = [], top = 0, bottom = m.length - 1, left = 0, right = m[0].length - 1;
  while (top <= bottom && left <= right) {
    for (var j = left; j <= right; j++) out.push(m[top][j]);
    top++;
    for (var i = top; i <= bottom; i++) out.push(m[i][right]);
    right--;
    if (top <= bottom) { for (var j = right; j >= left; j--) out.push(m[bottom][j]); bottom--; }
    if (left <= right) { for (var i = bottom; i >= top; i--) out.push(m[i][left]); left++; }
  }
  return out;
}
var a = make(2, 3, function (i, j) { return i * 3 + j + 1; });
var b = make(3, 2, function (i, j) { return (i + 1) * (j + 1); });
var id = make(3, 3, function (i, j) { return i === j ? 1 : 0; });
var sq = make(3, 3, function (i, j) { return i * 3 + j + 1; });
var flat = [].concat.apply([], sq);
var diag = sq.map(function (row, i) { return row[i]; });
[JSON.stringify(a), JSON.stringify(transpose(a)), JSON.stringify(multiply(a, b)), JSON.stringify(multiply(sq, id)) === JSON.stringify(sq), spiral(sq).join(), spiral(make(3, 4, function (i, j) { return i * 4 + j; })).join(), flat.join(), diag.join(), sq.flat().reduce(function (s, v) { return s + v; }, 0), a.map(function (r) { return r.join(' ') }).join('\n')].join('|');
