package me.lovelace.lovechat.textures;

/**
 * Централизованное хранилище base64 текстур голов (skull textures), используемых в GUI Lovechat.
 * <p>
 * Все base64-литералы текстур голов должны объявляться здесь, а не хардкодиться по месту
 * использования — так плагин следует единой точке правды для GUI-текстур, вместо дублирования
 * одних и тех же строк в разных классах.
 */
public final class HeadTextures {

    private HeadTextures() {
        // Утилитарный класс-константа, инстанцирование не предполагается
    }

    /**
     * Текстура стрелки «предыдущая страница» для пагинации истории чата.
     */
    public static final String PAGINATION_PREVIOUS =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjZkYWI3MjcxZjRmZjA0ZDU0NDAyMTkwNjdhMTA5YjVjMGMxZDFlMDFlYzYwMmMwMDIwNDc2ZjdlYjYxMjE4MCJ9fX0=";

    /**
     * Текстура стрелки «следующая страница» для пагинации истории чата.
     */
    public static final String PAGINATION_NEXT =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGFhMTg3ZmVkZTg4ZGUwMDJjYmQ5MzA1NzVlYjdiYTQ4ZDNiMWEwNmQ5NjFiZGM1MzU4MDA3NTBhZjc2NDkyNiJ9fX0=";
}
