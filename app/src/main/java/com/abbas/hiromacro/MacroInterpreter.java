package com.abbas.hiromacro;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreter for the core HiroMacro text language. */
final class MacroInterpreter {
    interface Host {
        boolean isRunning();
        void sleep(long millis) throws InterruptedException;
        void log(String message);
        void touchPath(List<int[]> points, long durationMs) throws Exception;
        void tap(int x, int y) throws Exception;
    }

    private static final Pattern TOKEN = Pattern.compile("#[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)|==|!=|<=|>=|[()+*/%<>-]");
    private final Host host;
    private final Map<String, Double> vars = new HashMap<>();
    private final Map<String, Integer> labels = new HashMap<>();
    private final Map<Integer, Integer> falseTarget = new HashMap<>();
    private final Map<Integer, Integer> skipTarget = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<int[]> strokePoints = new ArrayList<>();
    private long strokeDuration = 1;

    MacroInterpreter(Host host) { this.host = host; }

    void run(String source) throws Exception {
        compile(source);
        int pc = 0;
        long steps = 0;
        while (pc < lines.size() && host.isRunning()) {
            if (++steps > 250000) throw new IllegalStateException("Safety stop: instruction limit reached (possible infinite goto loop).");
            String line = lines.get(pc).trim();
            if (line.isEmpty()) { pc++; continue; }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("if ")) {
                pc = truth(expression(line.substring(3))) ? pc + 1 : falseTarget.getOrDefault(pc, pc + 1);
            } else if (lower.startsWith("elseif ")) {
                pc = truth(expression(line.substring(7))) ? pc + 1 : falseTarget.getOrDefault(pc, pc + 1);
            } else if (lower.equals("else")) {
                pc++;
            } else if (lower.equals("endif")) {
                pc++;
            } else if (lower.startsWith("goto ")) {
                String label = line.substring(5).trim();
                Integer target = labels.get(label.toLowerCase(Locale.ROOT));
                if (target == null) throw new IllegalArgumentException("Unknown label " + label + " at line " + (pc + 1));
                pc = target;
            } else if (lower.startsWith("sleep ")) {
                long ms = Math.max(0L, Math.min(60000L, Math.round(expression(line.substring(6)))));
                if (!strokePoints.isEmpty()) strokeDuration = Math.min(60000L, strokeDuration + ms);
                host.sleep(ms);
                pc++;
            } else if (lower.startsWith("touchdown ")) {
                String[] a = args(line.substring(10));
                if (a.length != 3) throw new IllegalArgumentException("touchDown needs: id x y (line " + (pc + 1) + ")");
                flushStroke();
                strokePoints.add(new int[]{integer(a[1]), integer(a[2])});
                strokeDuration = 1;
                pc++;
            } else if (lower.startsWith("touchmove ")) {
                String[] a = args(line.substring(10));
                if (a.length != 3) throw new IllegalArgumentException("touchMove needs: id x y (line " + (pc + 1) + ")");
                if (strokePoints.isEmpty()) throw new IllegalStateException("touchMove without touchDown at line " + (pc + 1));
                strokePoints.add(new int[]{integer(a[1]), integer(a[2])});
                pc++;
            } else if (lower.equals("touchup") || lower.startsWith("touchup ")) {
                if (strokePoints.isEmpty()) throw new IllegalStateException("touchUp without touchDown at line " + (pc + 1));
                flushStroke();
                pc++;
            } else if (lower.startsWith("touchpress ")) {
                String[] a = args(line.substring(11));
                if (a.length != 2) throw new IllegalArgumentException("touchpress needs x y at line " + (pc + 1));
                flushStroke();
                host.tap(integer(a[0]), integer(a[1]));
                pc++;
            } else if (lower.startsWith("toast ")) {
                host.log("TOAST: " + interpolate(line.substring(6)));
                pc++;
            } else if (lower.startsWith("log ")) {
                host.log(interpolate(line.substring(4)));
                pc++;
            } else if (line.startsWith("#") && line.contains("=")) {
                int eq = line.indexOf('=');
                String name = line.substring(0, eq).trim().substring(1).toLowerCase(Locale.ROOT);
                if (name.equals("red") || name.equals("green") || name.equals("blue")) {
                    throw new IllegalArgumentException("Pixel variable #" + name + " needs screen-color detection, which is not implemented yet (line " + (pc + 1) + ").");
                }
                vars.put(name, expression(line.substring(eq + 1)));
                pc++;
            } else if (lower.startsWith("device:") || lower.startsWith("screen_size:")) {
                pc++;
            } else {
                throw new IllegalArgumentException("Unsupported or malformed command at line " + (pc + 1) + ": " + line);
            }
        }
        flushStroke();
    }

    private void compile(String source) {
        lines.clear(); labels.clear(); falseTarget.clear(); skipTarget.clear();
        Deque<IfBlock> stack = new ArrayDeque<>();
        String[] sourceLines = source.split("\\r?\\n");
        for (String raw : sourceLines) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("//") || s.startsWith(";")) continue;
            int comment = s.indexOf("//");
            if (comment >= 0) s = s.substring(0, comment).trim();
            if (s.isEmpty()) continue;
            if (s.startsWith(":")) {
                String label = s.substring(1).trim().toLowerCase(Locale.ROOT);
                labels.put(label, lines.size());
                continue;
            }
            lines.add(s);
            int i = lines.size() - 1;
            String l = s.toLowerCase(Locale.ROOT);
            if (l.startsWith("if ")) {
                stack.push(new IfBlock(i));
            } else if (l.startsWith("elseif ")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("elseif without if at line " + (i + 1));
                IfBlock b = stack.peek();
                b.branches.add(i);
                falseTarget.put(b.lastBranch, i);
                b.lastBranch = i;
            } else if (l.equals("else")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("else without if at line " + (i + 1));
                IfBlock b = stack.peek();
                falseTarget.put(b.lastBranch, i);
                b.lastBranch = i;
                b.hasElse = true;
            } else if (l.equals("endif")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("endif without if at line " + (i + 1));
                IfBlock b = stack.pop();
                falseTarget.put(b.lastBranch, i + 1);
                for (int branch : b.branches) skipTarget.put(branch, i + 1);
                skipTarget.put(b.first, i + 1);
                skipTarget.put(i - 1, i + 1);
            }
        }
        if (!stack.isEmpty()) throw new IllegalArgumentException("Missing endif for if at line " + (stack.peek().first + 1));
        // Each conditional branch jumps past the rest of its chain when it evaluates true.
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).toLowerCase(Locale.ROOT);
            if (l.startsWith("if ") || l.startsWith("elseif ")) {
                int depth = 0;
                for (int j = i + 1; j < lines.size(); j++) {
                    String n = lines.get(j).trim().toLowerCase(Locale.ROOT);
                    if (n.startsWith("if ")) depth++;
                    else if (n.equals("endif")) {
                        if (depth == 0) { skipTarget.put(i, j + 1); break; }
                        depth--;
                    }
                }
            }
        }
    }

    private static final class IfBlock {
        final int first;
        int lastBranch;
        boolean hasElse;
        final List<Integer> branches = new ArrayList<>();
        IfBlock(int i) { first = i; lastBranch = i; branches.add(i); }
    }

    private void flushStroke() throws Exception {
        if (strokePoints.isEmpty()) return;
        if (strokePoints.size() == 1) host.tap(strokePoints.get(0)[0], strokePoints.get(0)[1]);
        else host.touchPath(new ArrayList<>(strokePoints), Math.max(1L, strokeDuration));
        strokePoints.clear();
        strokeDuration = 1;
    }

    private static String[] args(String s) { return s.trim().split("\\s+"); }
    private int integer(String s) { return (int) Math.round(expression(s)); }
    private String interpolate(String s) {
        Matcher m = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Double v = vars.get(m.group(1).toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : Long.toString(Math.round(v))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private double expression(String input) {
        Matcher m = TOKEN.matcher(input);
        List<String> t = new ArrayList<>();
        while (m.find()) t.add(m.group());
        Expr p = new Expr(t);
        double value = p.compare();
        if (p.hasNext()) throw new IllegalArgumentException("Invalid expression: " + input);
        return value;
    }
    private static boolean truth(double x) { return x != 0 && !Double.isNaN(x); }

    private final class Expr {
        final List<String> t; int p = 0;
        Expr(List<String> t) { this.t = t; }
        boolean hasNext() { return p < t.size(); }
        String peek() { return hasNext() ? t.get(p) : ""; }
        boolean take(String s) { if (peek().equals(s)) { p++; return true; } return false; }
        double compare() {
            double a = add();
            while (true) {
                String op = peek();
                if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
                    p++; double b = add();
                    switch (op) {
                        case "==": a = a == b ? 1 : 0; break;
                        case "!=": a = a != b ? 1 : 0; break;
                        case "<": a = a < b ? 1 : 0; break;
                        case ">": a = a > b ? 1 : 0; break;
                        case "<=": a = a <= b ? 1 : 0; break;
                        case ">=": a = a >= b ? 1 : 0; break;
                    }
                } else return a;
            }
        }
        double add() {
            double a = mul();
            while (true) {
                if (take("+")) a += mul();
                else if (take("-")) a -= mul();
                else return a;
            }
        }
        double mul() {
            double a = unary();
            while (true) {
                if (take("*")) a *= unary();
                else if (take("/")) { double b = unary(); if (b == 0) throw new ArithmeticException("division by zero"); a /= b; }
                else if (take("%")) { double b = unary(); if (b == 0) throw new ArithmeticException("division by zero"); a %= b; }
                else return a;
            }
        }
        double unary() {
            if (take("-")) return -unary();
            if (take("+")) return unary();
            if (take("(")) { double v = compare(); if (!take(")")) throw new IllegalArgumentException("Missing )"); return v; }
            String v = peek();
            if (v.isEmpty()) throw new IllegalArgumentException("Missing expression value");
            p++;
            if (v.startsWith("#")) {
                Double n = vars.get(v.substring(1).toLowerCase(Locale.ROOT));
                if (n == null) throw new IllegalArgumentException("Variable " + v + " is not set");
                return n;
            }
            if (Character.isDigit(v.charAt(0)) || v.charAt(0) == '.') return Double.parseDouble(v);
            throw new IllegalArgumentException("Unexpected token " + v);
        }
    }
}
