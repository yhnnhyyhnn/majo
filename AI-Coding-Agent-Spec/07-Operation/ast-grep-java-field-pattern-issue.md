# 上游 Issue 草稿：ast-grep Java 字段声明模式无法匹配

> 目标仓库：https://github.com/ast-grep/ast-grep（待用户用 GitHub 账号提交；
> 本文件为提交内容底稿，2026-09 由 majo 项目 ADR-0010 集成测试中发现）。
> 已在 ast-grep 0.45.3（Windows x64，npm 发行版）复现；判断与平台无关。

---

**Title**: [Java] Field declarations can never match patterns — even exact literals return zero matches

**Body**:

## Description

Java *field declarations* are silently unmatchable by ast-grep patterns in
v0.45.3. Even an **exact literal** pattern that copies a line from the file
returns zero matches with no error. Local variable declarations, statements
and method declarations match fine, so this is specific to fields
(`field_declaration` nodes in tree-sitter-java).

## Repro

`Field.java`:

```java
public class Field {
    private int x = 1;
    int y = 2;
}
```

Commands and results:

```console
$ ast-grep run -p 'private int x = 1;' -l java Field.java
(no output)

$ ast-grep run -p 'int y = 2;' -l java Field.java
(no output)

$ ast-grep run -p 'private int $F = $$$RHS;' -l java Field.java
(no output)

$ ast-grep run -p 'int $F = $$$RHS;' -l java Field.java
(no output)
```

All four should match line 2 / line 3 respectively. No error is reported —
the search is silently empty.

## What *does* work (same file, ast-grep 0.45.3)

- `public void $M($$$P) { $$$B }` — method declarations match.
- `List<$E> $V = $$$RHS;` — **local** variable declarations match, while the
  same pattern does not match a field with an initializer
  (`private List<String> names = List.of();` inside a class body).
- Statement patterns, `@$NAME` annotation patterns, for-each patterns.

## Additional observations

- A meta-variable in the modifier position (`$MOD int x = 1;`) fails to parse:
  `Cannot parse query as a valid pattern` → `Multiple AST nodes are detected`.
  So there is currently no way at all to target field declarations:
  neither with modifiers (silently unmatched) nor without (modifier mismatch).
- Suspected cause: patterns are parsed in a context where
  `private int x = 1;` does not reduce to the same node kind as
  `field_declaration` in real class bodies (or modifiers prevent the pattern
  from reducing to a single node), so the candidate never equals the matched
  node kind. Exact literals failing suggests the pattern-side parse differs
  from the source-side parse.

## Expected behavior

An exact-literal pattern copied from a Java class body should match that
line. Ideally field declarations become matchable both with and without
modifiers, and a `$MOD`-style modifier meta-variable is considered.

## Environment

- ast-grep 0.45.3 (npm distribution, Windows x64)
- tree-sitter-java default grammar bundled with this version
