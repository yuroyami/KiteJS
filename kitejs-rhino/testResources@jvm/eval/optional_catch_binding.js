var out = [];
try { throw new Error('x'); } catch { out.push('caught without binding'); }
try { JSON.parse('{'); } catch { out.push('parse failed'); } finally { out.push('finally'); }
function attempt(f) { try { return f(); } catch { return 'failed'; } }
out.push(attempt(function () { return 'fine'; }), attempt(function () { throw 1; }));
out.join('|');
