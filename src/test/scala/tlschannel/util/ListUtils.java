package tlschannel.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListUtils {

    // Reversed method not present before Java 21.

    public static <T> List<T> reversed(List<T> list) {
        @SuppressWarnings("unchecked")
        List<T> reversedSizes = (List<T>) new ArrayList<>(list).clone();
        Collections.reverse(reversedSizes);
        return reversedSizes;
    }
}
