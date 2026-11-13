package net.voidclear.v2.rule;

/**
 * 一次判定的结果：清不清，以及为什么。
 *
 * @param remove true = 该扫掉
 * @param reason 判定依据，{@code named} / {@code keep:animals} / {@code no-rule} 之类
 */
public record Outcome(boolean remove, String reason) {

    public static Outcome remove(String reason) {
        return new Outcome(true, reason);
    }

    public static Outcome keep(String reason) {
        return new Outcome(false, reason);
    }
}
