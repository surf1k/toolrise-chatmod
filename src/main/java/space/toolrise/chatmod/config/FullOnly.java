package space.toolrise.chatmod.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройка есть только в полной версии. В лёгкой такое поле не пишется
 * в {@code config.json} и не читается из него: настройки того, чего в сборке
 * нет, — это мусор, из-за которого файл выглядит сломанным.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FullOnly {
}
