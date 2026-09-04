if (a) {
  b();
}
if (a) {
  b();
} else {
  c();
}
if (a) b();
if (a) b(); else c();
if (a) {
  b();
} else if (c) {
  d();
} else {
  e();
}
while (a) {
  b();
}
while (a) b();
do {
  a();
} while (b);
for (var i = 0; i < 10; i++) {
  a();
}
for (;;) {
  break;
}
for (i = 0; ; i++) {
  break;
}
