# 上游 Issue 草稿：ast-grep Java pattern 无法匹配字段声明（0.45.3 实测，官方目录示例亦失效）

> 目标仓库：https://github.com/ast-grep/ast-grep/issues（待用户用 GitHub 账号提交）。
> 2026-09-18 由 majo 项目在 ast-grep **0.45.3**（npm 发行版，当前最新版，
> Windows x64）实测整理；判断与平台无关。
>
> 与初稿的差异：核实版本后确认不是"版本太低"。官方目录
> <https://ast-grep.github.io/catalog/java/> 已记载"带修饰符的字段 pattern
> 不可匹配、`$MOD` 无法解析"，并给出 `kind: field_declaration` 结构化规则
> 作为正解（实测有效）。**本 issue 的增量**：目录中声称可用的 pattern 示例
> `String $F;` 在 0.45.3 上同样零匹配——即当前版本 pattern 完全无法到达
> 字段声明，目录文档与行为存在漂移。

---

**Title**: [Java] No pattern can match field declarations in 0.45.3 — the catalog's own `String $F;` example returns zero matches

**Body**:

## Summary

On ast-grep 0.45.3, **no text pattern matches Java field declarations** —
not even the exact literal copied from the file, and not even the
modifier-free `String $F;` example shown as "working" in the official Java
catalog. Structural rules with `kind: field_declaration` work fine, so this
is specifically about pattern-side parsing never producing a
`field_declaration` node.

The catalog (https://ast-grep.github.io/catalog/java/) documents that
patterns fail *with modifiers/annotations* and suggests `String $F;` works
without them. On 0.45.3 that no longer holds — the docs and the code have
drifted.

## Repro

`Field.java`:

```java
public class Field {
    private int x = 1;
    int y = 2;
    String name;
    String label = "hi";
}
```

All of these return **zero matches, no error**:

```console
$ ast-grep run -p 'String name;' -l java Field2.java
(no output)
$ ast-grep run -p 'String $F;' -l java Field2.java        # catalog example
(no output)
$ ast-grep run -p 'int y = 2;' -l java Field2.java
(no output)
$ ast-grep run -p 'private int $F = $$$RHS;' -l java Field2.java
(no output)
```

## What does work

The catalog's structural-rule workaround matches every field regardless of
modifiers:

```console
$ ast-grep scan --inline-rules 'rule:
  kind: field_declaration
language: java
' --json=compact Field.java
# → all four fields, including `private int x = 1;`
```

Methods, locals, annotations (`@$NAME`), and for-each statement patterns all
match as expected — only field declarations are unreachable by patterns.

## Notes

- `$MOD String $F;` fails to parse ("Multiple AST nodes are detected") —
  matches the catalog's own observation; there is no modifier meta-variable.
- Suspected cause: patterns are parsed in a context that never reduces to
  `field_declaration` (statement/expression context), so the candidate node
  kind never equals the source-side kind.

## Ask

1. Make text patterns able to match `field_declaration` (at minimum exact
   literals and modifier-free patterns as the catalog promises), or
2. If that is a tree-sitter limitation by design, update the catalog page to
   stop advertising `String $F;` as a working example and point to
   `kind: field_declaration` rules as the only option.

## Environment

- ast-grep 0.45.3 (npm `@ast-grep/cli`, Windows x64)
- bundled tree-sitter-java grammar

---

### majo 侧已采取的措施（无需上游动作）

`ast_search` 工具新增可选 `kind` 参数（`scan --inline-rules` 路径），
Java 字段搜索立即可用；pattern 路径保持不变。
