# Diffing tools


This repository provides a set of utilities for computing and exporting Abstract Syntax Tree (AST) differences between two versions of Java or Kotlin projects using GumTree.
It also supports exporting tree structures, computing statistics, and serializing ASTs into multiple formats (JSON, Lisp, Tree-sitter-like).

## Project structure


```
src/
 ├── Helpers.java 
 ├── GumTreeExtractor.java
 ├── Main.java
 └── Serializers.java
```
The project uses Maven to handle dependencies.

## Example usage


```
public static void main(String[] args) {

        String srcPath = "path/to/beforeProject";
        String dstPath = "path/to/afterProject";

        try {
            saveProjectDiffToJson(srcPath, dstPath, "results/diff.json", "java");
            System.out.println("Project diff JSON written to results/diff.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }


    }
    

```
The result will be a JSON formatted as follows
```
{
"before": "pathBefore",
"after": "pathAfter",
"generatedAt": "Fri Nov 14 17:44:56 CET 2025",
"files": [
{
"path": "Example.java",
"status": "modified",
"actions": [
{
"action": "TreeDelete",
"nodeTree": "(VariableDeclarationStatement (PrimitiveType \"int\") (VariableDeclarationFragment (SimpleName \"i\") (NumberLiteral \"10\")))",
"treeBefore": "(CompilationUnit (TypeDeclaration (Modifier \"public\") (TYPE_DECLARATION_KIND \"class\") (SimpleName \"Example\") (MethodDeclaration (Modifier \"public\") (PrimitiveType \"void\") (SimpleName \"hello\") (Block (VariableDeclarationStatement (PrimitiveType \"int\") (VariableDeclarationFragment (SimpleName \"i\") (NumberLiteral \"10\")))))))",
"treeAfter": "(CompilationUnit (TypeDeclaration (Modifier \"public\") (TYPE_DECLARATION_KIND \"class\") (SimpleName \"Example\") (MethodDeclaration (Modifier \"public\") (PrimitiveType \"void\") (SimpleName \"hello\") (Block))))"
}
],
"diffTimeMs": 318
}
]
}

```