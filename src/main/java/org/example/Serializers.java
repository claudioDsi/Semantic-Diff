package org.example;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.Type;
public class Serializers {



    /** ---------- Utilities shared by both serializers ---------- */
    private static String safeTypeName(Type ty) {
        try {
            String n = ty.toString();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return String.valueOf(ty);
    }

    private static String escapeForSexp(String s) {
        if (s == null) return "";
        // Minimal escaping for string atoms
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /** ---------- 1) Lisp S-expression serializer ----------
     * Format:
     *   (Type "label" child1 child2 ...)
     * - label is included only if present (Tree#getLabel not empty)
     * - children are nested S-expressions
     * - single-line, compact
     */
    public static String toLisp(Tree root) {
        StringBuilder sb = new StringBuilder(256);
        toLispRec(root, sb);
        return sb.toString();
    }

    private static void toLispRec(Tree t, StringBuilder sb) {
        sb.append('(').append(safeTypeName(t.getType()));
        if (hasText(t.getLabel())) {
            sb.append(' ').append('"').append(escapeForSexp(t.getLabel())).append('"');
        }
        for (Tree c : t.getChildren()) {
            sb.append(' ');
            toLispRec(c, sb);
        }
        sb.append(')');
    }

    /** ---------- 2) Tree-sitter–style pretty serializer ----------
     * Format (approximate TS node dumps):
     *   (type
     *     (childType "label")
     *     (childType
     *       (grandChild ...)))
     * - one node per line with indentation
     * - label shown as a string atom after the type when present
     * - no positions/fields (we don't have named fields from GumTree)
     */
    public static String toTreeSitterString(Tree root) {
        StringBuilder sb = new StringBuilder(256);
        toTsRec(root, 0, sb);
        return sb.toString();
    }

    private static void toTsRec(Tree t, int depth, StringBuilder sb) {
        indent(sb, depth).append('(').append(safeTypeName(t.getType()));
        if (hasText(t.getLabel())) {
            sb.append(' ').append('"').append(escapeForSexp(t.getLabel())).append('"');
        }
        if (t.getChildren().isEmpty()) {
            sb.append(')').append('\n');
            return;
        }
        sb.append('\n');
        for (Tree c : t.getChildren()) {
            toTsRec(c, depth + 1, sb);
        }
        indent(sb, depth).append(')').append('\n');
    }

    private static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb;
    }


}
