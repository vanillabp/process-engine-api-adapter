package io.vanillabp.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the main sources of this repository and finds every place where a BPMN error code is
 * handed to the engine, so the build can require each of them to scope it first.
 * <p>
 * Under the name-clash-avoidance mode <code>use-prefix</code> the <code>errorCode</code> of a
 * <code>bpmn:error</code> is deployed with the workflow module in front of it, like every other
 * identifier of that module. A code sent on unscoped is a code no boundary event of the
 * deployed model catches, and nothing anywhere says so: the engine takes the command and the
 * workflow walks past the error path. That is why this is read out of the source rather than
 * covered by a test per path. An unscoped code compiles perfectly well, and the paths are added
 * one at a time.
 * <p>
 * The finding this comes from had two places, and the second one was found only because
 * somebody asked for it by hand.
 */
final class ErrorCodesReachingTheEngine {

  /**
   * The constructions which put a BPMN error code into a command for the engine. The plain
   * name is listed beside the adapter's own subclass, because a command built directly from
   * the API's class reaches the engine just as well.
   */
  private static final Pattern ERROR_COMMAND = Pattern
      .compile("new\\s+(?:Pea)?CompleteTaskByErrorCmd\\s*\\(");

  /**
   * What an error code has to be built by: a call to one of the scoping helpers, whichever
   * class it is read from. Everything the helpers are named starts with <code>scoped</code>,
   * and that is the part a new call site has to get right.
   */
  private static final Pattern SCOPING_HELPER_CALL = Pattern
      .compile("^(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*scoped[A-Za-z]*\\s*\\(");

  /**
   * One place where an error code reaches the engine without being scoped.
   *
   * @param file The source file, relative to the repository root
   * @param line The line the command is built on, so a failing build can be opened there
   * @param expression The error-code argument as it stands in the source
   */
  record UnscopedErrorCode(Path file, int line, String expression) {
  }

  private ErrorCodesReachingTheEngine() {
  }

  /**
   * How many places of this repository hand an error code to the engine, scoped or not. A
   * check asserts this is not zero, because a scan which finds nothing passes every rule.
   *
   * @param repositoryRoot The repository's root directory
   * @return The number of call sites found
   */
  static int callSites(
      final Path repositoryRoot) {

    return mainSources(repositoryRoot)
        .stream()
        .mapToInt(file -> errorCodeArgumentsIn(read(file)).size())
        .sum();

  }

  /**
   * The places which hand an error code to the engine as the caller wrote it.
   *
   * @param repositoryRoot The repository's root directory
   * @return What was found, in a stable order
   */
  static List<UnscopedErrorCode> unscopedErrorCodes(
      final Path repositoryRoot) {

    final var offenders = new ArrayList<UnscopedErrorCode>();
    for (final var file : mainSources(repositoryRoot)) {
      final var source = read(file);
      for (final var argument : errorCodeArgumentsIn(source)) {
        if (!SCOPING_HELPER_CALL.matcher(argument.expression()).find()) {
          offenders
              .add(
                  new UnscopedErrorCode(
                      repositoryRoot.relativize(file), argument.line(), argument.expression()));
        }
      }
    }
    return List.copyOf(offenders);

  }

  /**
   * The message of a failing check: which call sites, and what to do about them.
   *
   * @param offenders What {@link #unscopedErrorCodes(Path)} returned
   * @return A message naming every offending call site
   */
  static String describeUnscopedErrorCodes(
      final Collection<UnscopedErrorCode> offenders) {

    return """
        %d place(s) hand a BPMN error code to the engine without scoping it:
        %s
        Under 'use-prefix' the model carries the code with the workflow module in front of it, \
        so an unscoped code is one no boundary event catches and the workflow walks past the \
        error path without a word. Put the code through the scoping helper of the class you are \
        in, the way the other call sites do."""
        .formatted(
            offenders.size(),
            offenders
                .stream()
                .map(offender -> "  %s:%d: %s".formatted(offender.file(), offender.line(), offender.expression()))
                .collect(Collectors.joining("\n")));

  }

  /**
   * The error-code argument of every command built in one source, with the line it stands on.
   */
  private record ErrorCodeArgument(int line, String expression) {
  }

  private static List<ErrorCodeArgument> errorCodeArgumentsIn(
      final String source) {

    final var arguments = new ArrayList<ErrorCodeArgument>();
    final var command = ERROR_COMMAND.matcher(source);
    while (command.find()) {
      // the error code is the SECOND argument of every one of these commands, the task id
      // being the first
      final var errorCode = argumentAt(source, command.end(), 1);
      if (errorCode != null) {
        arguments.add(new ErrorCodeArgument(lineOf(source, command.start()), errorCode));
      }
    }
    return arguments;

  }

  /**
   * One argument of an argument list, read from the source rather than from a parsed tree.
   * <p>
   * The list is split at the commas which stand at bracket depth zero and outside a string, a
   * character literal or a text block, so an argument which is itself a call keeps its own
   * commas. Everything else about the expression is left as it is, apart from the line breaks
   * and the indentation a long call is wrapped with, which are folded into single spaces so a
   * call site reads the same however it happens to be formatted. Comments are gone by the time
   * this reads anything, see {@link #withoutComments(String)}.
   *
   * @param source The whole file
   * @param from The offset right behind the opening bracket
   * @param index Which argument, counted from zero
   * @return The argument, or <code>null</code> where the list holds fewer
   */
  private static String argumentAt(
      final String source,
      final int from,
      final int index) {

    var depth = 0;
    var argument = 0;
    var start = from;
    for (var at = from; at < source.length(); at++) {
      final var skipped = endOfLiteral(source, at);
      if (skipped > at) {
        at = skipped - 1;
        continue;
      }
      final var character = source.charAt(at);
      if ((character == '(') || (character == '[') || (character == '{')) {
        depth++;
      } else if ((character == ']') || (character == '}')) {
        depth--;
      } else if (character == ')') {
        if (depth == 0) {
          return argument == index
              ? folded(source.substring(start, at))
              : null;
        }
        depth--;
      } else if ((character == ',') && (depth == 0)) {
        if (argument == index) {
          return folded(source.substring(start, at));
        }
        argument++;
        start = at + 1;
      }
    }
    return null;

  }

  /**
   * The offset right behind the string, character literal or text block starting at the given
   * position, or that position itself where none of them starts there.
   *
   * @param source The whole file
   * @param at Where to look
   * @return Where to carry on reading
   */
  private static int endOfLiteral(
      final String source,
      final int at) {

    if (source.startsWith("\"\"\"", at)) {
      return endOf(source, at + 3, "\"\"\"");
    }
    final var character = source.charAt(at);
    if ((character == '"') || (character == '\'')) {
      return endOfQuoted(source, at + 1, character);
    }
    return at;

  }

  private static int endOf(
      final String source,
      final int from,
      final String terminator) {

    final var end = source.indexOf(terminator, from);
    return end < 0
        ? source.length()
        : end + terminator.length();

  }

  private static int endOfQuoted(
      final String source,
      final int from,
      final char quote) {

    for (var at = from; at < source.length(); at++) {
      final var character = source.charAt(at);
      if (character == '\\') {
        at++;
      } else if (character == quote) {
        return at + 1;
      }
    }
    return source.length();

  }

  private static String folded(
      final String expression) {

    return expression
        .replaceAll("\\s+", " ")
        .trim();

  }

  private static int lineOf(
      final String source,
      final int offset) {

    return (int) source
        .substring(0, offset)
        .chars()
        .filter(character -> character == '\n')
        .count() + 1;

  }

  private static List<Path> mainSources(
      final Path repositoryRoot) {

    try (var files = Files.walk(repositoryRoot)) {
      return files
          .filter(ErrorCodesReachingTheEngine::isMainSourceFile)
          .sorted()
          .toList();
    } catch (final IOException cannotRead) {
      throw new UncheckedIOException(
          "Could not read the main sources below '%s'".formatted(repositoryRoot), cannotRead);
    }

  }

  private static boolean isMainSourceFile(
      final Path file) {

    final var path = file
        .toString()
        .replace('\\', '/');
    return path.endsWith(".java") && path.contains("/src/main/java/") && !path.contains("/target/");

  }

  private static String read(
      final Path file) {

    try {
      return withoutComments(Files.readString(file, StandardCharsets.UTF_8));
    } catch (final IOException cannotRead) {
      throw new UncheckedIOException("Could not read '%s'".formatted(file), cannotRead);
    }

  }

  /**
   * The same source with every comment blanked out, character for character, so that what is
   * left stands where it stood and every line number still holds.
   * <p>
   * Two things need this. A javadoc block which shows a call in prose is not a call site, and
   * a comment standing inside an argument list would end up in the expression this reads.
   *
   * @param source The file as it is written
   * @return The file with its comments turned into spaces
   */
  private static String withoutComments(
      final String source) {

    final var stripped = new StringBuilder(source);
    for (var at = 0; at < stripped.length(); at++) {
      final var literal = endOfLiteral(source, at);
      if (literal > at) {
        at = literal - 1;
        continue;
      }
      final int end;
      if (source.startsWith("//", at)) {
        end = endOf(source, at + 2, "\n") - 1;
      } else if (source.startsWith("/*", at)) {
        end = endOf(source, at + 2, "*/");
      } else {
        continue;
      }
      for (var blank = at; blank < Math.min(end, stripped.length()); blank++) {
        if (stripped.charAt(blank) != '\n') {
          stripped.setCharAt(blank, ' ');
        }
      }
      at = end - 1;
    }
    return stripped.toString();

  }

}
