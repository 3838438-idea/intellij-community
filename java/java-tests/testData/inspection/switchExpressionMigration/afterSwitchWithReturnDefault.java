// "Replace with 'switch' expression" "true"
import java.util.*;

class SwitchExpressionMigration {
  private static String m(int n) {
      switch (n) {
          case 1 -> "a";
          case 2 -> "b";
          default -> "c";
      }
  }
}