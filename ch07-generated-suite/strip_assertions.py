#!/usr/bin/env python3
"""
Remove every verification from the generated tests while preserving Arrange and Act.

The subtlety: generated tests routinely put the ACT inside the assertion,
  assertEquals(expected, service.checkout(...));
so simply deleting the statement would delete the act as well and depress coverage
for reasons that have nothing to do with verification. So assertions are replaced by
a sink that still evaluates every argument expression but checks nothing:

  assertEquals(a, b);                  ->  sink(a, b);
  assertAmount("0.50", calc.x(...));   ->  sink("0.50", calc.x(...));
  assertThrows(T.class, () -> BODY);   ->  try { BODY; } catch (Throwable ignored) { }
  verify(mock).save(x);                ->  (statement deleted)
  order.verify(mock).save(x);          ->  (statement deleted)
  verifyNoInteractions(mock);          ->  (statement deleted)

Stubbing (when/thenReturn/given) is Arrange and is kept untouched.
Assertion helper *declarations* are left in place (unused, harmless).
"""
import re, glob, os

ASSERTS = ('assertEquals', 'assertTrue', 'assertFalse', 'assertSame',
           'assertNotEquals', 'assertNotNull', 'assertNull', 'assertAmount')
VERIFIES = ('verifyNoInteractions', 'verifyNoMoreInteractions', 'verify')

SINK = ('\n    @SuppressWarnings("unused")\n'
        '    private static void sink(Object... evaluatedButUnchecked) { }\n')


def find_matching(src, i):
    depth, j, n = 0, i, len(src)
    while j < n:
        c = src[j]
        if c == '"':
            j += 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise ValueError('unbalanced paren')


def stmt_end(src, i):
    j = i
    while j < len(src):
        if src[j] == ';':
            return j + 1
        if src[j] == '(':
            j = find_matching(src, j)
            continue
        j += 1
    return len(src)


def split_args(arglist):
    """Split a parenthesised argument list at top-level commas."""
    args, depth, cur = [], 0, ''
    i, n = 0, len(arglist)
    while i < n:
        c = arglist[i]
        if c == '"':
            j = i + 1
            while j < n and arglist[j] != '"':
                j += 2 if arglist[j] == '\\' else 1
            cur += arglist[i:j + 1]; i = j + 1; continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == ',' and depth == 0:
            args.append(cur); cur = ''; i += 1; continue
        cur += c; i += 1
    if cur.strip():
        args.append(cur)
    return args


def at_statement_start(src, pos):
    """True if pos begins a statement (not a declaration or an argument)."""
    k = pos - 1
    while k >= 0 and src[k] in ' \t':
        k -= 1
    if k < 0:
        return False
    if src[k] not in ';{}\n':
        return False
    # reject method declarations: "void assertAmount(" / "static void assertAmount("
    line_start = src.rfind('\n', 0, pos) + 1
    line = src[line_start:pos]
    return not re.search(r'\b(void|private|public|protected|static)\s*$', line)


def transform(src):
    counts = {'sunk': 0, 'verify_deleted': 0, 'throws': 0}
    pat = re.compile(r'\b(' + '|'.join(ASSERTS + VERIFIES) + r'|assertThrows)\s*\(')
    out, i = [], 0
    while True:
        m = pat.search(src, i)
        if not m:
            out.append(src[i:])
            break
        name = m.group(1)
        start = m.start()
        # a verify chained off a receiver (order.verify) starts earlier
        stmt_start = start
        if name == 'verify':
            k = start - 1
            while k >= 0 and (src[k].isalnum() or src[k] in '_.'):
                k -= 1
            k += 1
            if k < start:
                stmt_start = k
        if not at_statement_start(src, stmt_start):
            out.append(src[i:m.end()])
            i = m.end()
            continue
        open_paren = src.index('(', m.start())
        close = find_matching(src, open_paren)
        end = stmt_end(src, close)
        out.append(src[i:stmt_start])
        if name == 'assertThrows':
            inner = src[open_paren + 1:close - 1]
            lam = inner.find('->')
            body = inner[lam + 2:].strip() if lam != -1 else ''
            if body.startswith('{') and body.endswith('}'):
                body = body[1:-1]
            body = body.strip().rstrip(';')
            out.append('try { ' + body + '; } catch (Throwable ignored) { }')
            counts['throws'] += 1
        elif name in VERIFIES:
            counts['verify_deleted'] += 1
        else:
            # keep every argument that can carry an ACT; drop lambda message
            # suppliers, which are part of the verification, not the act
            inner = src[open_paren + 1:close - 1]
            kept = [a for a in split_args(inner) if '->' not in a]
            out.append('sink(' + ','.join(kept) + ');' if kept else ';')
            counts['sunk'] += 1
        i = end
    return ''.join(out), counts


def inject_sink(src):
    """Add the sink helper to every class/nested-class that needs one."""
    if 'private static void sink(' in src:
        return src
    idx = src.rindex('}')
    return src[:idx] + SINK + src[idx:]


def main(root):
    total = {'sunk': 0, 'verify_deleted': 0, 'throws': 0}
    for f in sorted(glob.glob(root + '/src/test/java/ie/ardaralibraries/bookline/*/*.java')):
        src = open(f).read()
        new, c = transform(src)
        new = inject_sink(new)
        open(f, 'w').write(new)
        for k in total:
            total[k] += c[k]
        print(f'{os.path.basename(f):32} sunk={c["sunk"]:3} '
              f'verify_deleted={c["verify_deleted"]:3} assertThrows_unwrapped={c["throws"]:3}')
    print(f'\nTOTAL  assertions neutralised={total["sunk"]}  '
          f'verifications deleted={total["verify_deleted"]}  '
          f'assertThrows unwrapped={total["throws"]}')
    print(f'All verification removed: {sum(total.values())} statements.')


if __name__ == '__main__':
    import sys
    main(sys.argv[1] if len(sys.argv) > 1 else 'proj_noassert')
