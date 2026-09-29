package ru.chmod777.trubofiesta.algo;

/**
 * Куда алгоритм пишет шаги своей работы. Расчёт идёт минуты, и инженер должен видеть,
 * на чём он сейчас и сколько это заняло. Что делать с сообщением — дописать время,
 * положить в журнал задания, отправить в веб-интерфейс — решает вызывающий.
 */
@FunctionalInterface
public interface ProgressLog {

    void step(String message);

    /** Журнал, который всё выбрасывает: для тестов и разовых прогонов. */
    static ProgressLog none() {
        return message -> {
        };
    }
}
