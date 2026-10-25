package io.blueprints.core.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Пояснения к члену обзора из комментариев исходника — чтобы по графу было понятно, что делает код:
 * <ul>
 *   <li>{@code section} — раздел класса из баннерного комментария выше члена:
 *       {@code // ---- / // Книги / // ----} или {@code // ===== Книги =====};</li>
 *   <li>{@code doc} — первая фраза javadoc или комментария прямо над членом;</li>
 *   <li>{@code entry} — точка входа {@code public static void main(String[])}.</li>
 * </ul>
 */
final class MemberNotes {

    /** Строка-разделитель баннера: только -, =, *, _, # (от 4 символов). */
    private static final Pattern SEPARATOR = Pattern.compile("^[-=*_#~/\\s]{4,}$");
    /** Заголовок внутри однострочного баннера: «==== Книги ====». */
    private static final Pattern INLINE_BANNER = Pattern.compile("^[-=*_#~]{3,}\\s*(.*?\\p{L}.*?)\\s*[-=*_#~]{3,}$");
    private static final int MAX_DOC = 160;

    private MemberNotes() {
    }

    static Optional<String> section(BodyDeclaration<?> member) {
        int line = member.getBegin().map(p -> p.line).orElse(-1);
        Node owner = member.getParentNode().orElse(null);
        CompilationUnit cu = member.findCompilationUnit().orElse(null);
        if (line < 0 || owner == null || cu == null) {
            return Optional.empty();
        }
        int ownerStart = owner.getBegin().map(p -> p.line).orElse(0);
        // баннер стоит между членами типа, а не внутри тела какого-то из них
        List<int[]> inside = owner.getChildNodes().stream()
                .filter(n -> n instanceof BodyDeclaration<?> && n.getRange().isPresent())
                .map(n -> new int[] {n.getRange().get().begin.line, n.getRange().get().end.line})
                .toList();
        List<Comment> comments = cu.getAllContainedComments().stream()
                .filter(c -> c.getBegin().isPresent())
                .filter(c -> c.getBegin().get().line > ownerStart && c.getBegin().get().line < line)
                .filter(c -> inside.stream().noneMatch(r -> r[0] <= c.getBegin().get().line && c.getBegin().get().line <= r[1]))
                .sorted(Comparator.comparingInt(c -> c.getBegin().get().line))
                .toList();
        String found = null;
        for (int i = 0; i < comments.size(); i++) {
            String title = bannerTitle(comments, i);
            if (title != null) {
                found = title;
            }
        }
        return Optional.ofNullable(found);
    }

    private static String bannerTitle(List<Comment> comments, int i) {
        Comment c = comments.get(i);
        if (!(c instanceof LineComment)) {
            String text = c.getContent().strip().replaceAll("(?m)^\\s*\\*\\s?", "").strip();
            String[] lines = text.split("\n");
            if (lines.length >= 2 && SEPARATOR.matcher(lines[0].strip()).matches()) {
                return title(lines[1]);
            }
            return null;
        }
        String text = c.getContent().strip();
        var inline = INLINE_BANNER.matcher(text);
        if (inline.matches()) {
            return title(inline.group(1));
        }
        if (SEPARATOR.matcher(text).matches() || !text.matches(".*\\p{L}.*")) {
            return null;
        }
        int line = c.getBegin().get().line;
        boolean above = i > 0 && isSeparatorAt(comments.get(i - 1), line - 1);
        boolean below = i + 1 < comments.size() && isSeparatorAt(comments.get(i + 1), line + 1);
        return above && below ? title(text) : null;
    }

    private static boolean isSeparatorAt(Comment c, int line) {
        return c instanceof LineComment && c.getBegin().get().line == line
                && SEPARATOR.matcher(c.getContent().strip()).matches();
    }

    private static String title(String s) {
        String t = s.strip();
        return t.isEmpty() || t.length() > 80 ? null : t;
    }

    static Optional<String> doc(BodyDeclaration<?> member) {
        Comment c = member.getComment().orElse(null);
        if (c == null) {
            return Optional.empty();
        }
        String text;
        if (c instanceof JavadocComment j) {
            text = j.parse().getDescription().toText();
        } else {
            text = c.getContent().replaceAll("(?m)^\\s*\\*\\s?", "");
        }
        text = text.replaceAll("\\{@\\w+\\s+([^}]*)}", "$1").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip();
        if (text.isEmpty() || SEPARATOR.matcher(text).matches() || !text.matches(".*\\p{L}.*")) {
            return Optional.empty();
        }
        int dot = text.indexOf(". ");
        if (dot > 0 && dot < MAX_DOC) {
            text = text.substring(0, dot + 1);
        }
        return Optional.of(text.length() > MAX_DOC ? text.substring(0, MAX_DOC - 1) + "…" : text);
    }

    static boolean isEntryPoint(BodyDeclaration<?> member) {
        return member instanceof MethodDeclaration m && m.getNameAsString().equals("main") && m.isStatic()
                && m.getType().isVoidType() && m.getParameters().size() == 1
                && m.getParameter(0).getType().asString().replace(" ", "").matches("String(\\[])+|String\\.\\.\\.");
    }
}
