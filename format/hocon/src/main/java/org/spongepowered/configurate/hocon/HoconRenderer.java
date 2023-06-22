package org.spongepowered.configurate.hocon;

import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.impl.ConfigImplUtil;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.util.Strings;

import static org.spongepowered.configurate.loader.AbstractConfigurationLoader.CONFIGURATE_LINE_PATTERN;

public final class HoconRenderer {

    private final Options options;

    private final String computedIndent;
    private final String computedSpaceLeftOfSep;
    private final String computedSpaceRightOfSep;

    private final ConfigRenderOptions valueRenderOptions;

    private HoconRenderer(final Options options) {
        this.options = options;
        final String indentChar = Character.toString(this.options.indentCharacter.getIndentChar());
        this.computedIndent = Strings.repeat(indentChar, this.options.indent);

        this.computedSpaceLeftOfSep = Strings.repeat(" ", this.options.spacesBeforeSeparator);
        this.computedSpaceRightOfSep = Strings.repeat(" ", this.options.spacesAfterSeparator);

        if (this.options.stringQuoting == Options.StringQuoting.ALWAYS) {
            // concise defaults to `json` which will always quote strings
            this.valueRenderOptions = ConfigRenderOptions.concise();
        } else if (this.options.stringQuoting == Options.StringQuoting.WHEN_REQUIRED) {
            this.valueRenderOptions = ConfigRenderOptions.concise().setJson(false);
        } else {
            throw new IllegalStateException("stringQuoting value unexpected: " + this.options.stringQuoting);
        }
    }

    static HoconRenderer from(final Options options) {
        return new HoconRenderer(options);
    }

    String renderNode(final ConfigurationNode node) throws ConfigurateException {
        StringWriter writer = new StringWriter();
        this.renderNode(writer, node, 0);
        return writer.toString();
    }

    private void renderNode(final StringWriter writer, final ConfigurationNode node, final int indentLevel) throws ConfigurateException {
        if (node.isMap()) {
            if (indentLevel > 0) {
                // Only open maps when we aren't dealing with the root node
                writer.append('{');
                renderNewline(writer);
            }

            this.renderMap(writer, node.childrenMap(), indentLevel);

            if (indentLevel > 0) {
                // close map, again only when we aren't the root node
                // indentLevel - 1 because it's the closing brace
                renderIndent(writer, indentLevel - 1);
                writer.append('}');
                renderNewline(writer);
            }
        } else if (node.isList()) {
            this.renderList(writer, node.childrenList(), indentLevel);
        } else {
            this.renderScalar(writer, node.rawScalar(), indentLevel);
        }
    }

    private void renderComments(final StringWriter writer, final ConfigurationNode node, final int indentLevel) {
        if (!(node instanceof CommentedConfigurationNode)) {
            return;
        }

        final @Nullable String comment = ((CommentedConfigurationNode) node).comment();
        if (comment == null) {
            return;
        }

        for (String line : CONFIGURATE_LINE_PATTERN.split(comment)) {
            this.renderIndent(writer, indentLevel);
            writer.append(this.options.commentStyle.getCommentPrefix());
            writer.append(' ');
            writer.append(line);
            renderNewline(writer);
        }
    }

    private void renderMap(final StringWriter writer, final Map<Object, ? extends ConfigurationNode> map, final int indentLevel) throws ConfigurateException {
        for (final Map.Entry<Object, ? extends ConfigurationNode> entry : map.entrySet()) {
            final ConfigurationNode node = entry.getValue();

            renderComments(writer, node, indentLevel);
            renderIndent(writer, indentLevel);

            final String key = String.valueOf(entry.getKey());
            // TODO - add configuration option to always quote keys
            if (isSimpleKey(key)) {
                writer.append(key);
            } else {
                writer.append(ConfigImplUtil.renderJsonString(key));
            }

            if (!node.isMap() || this.options.objectSeparator == Options.ObjectSeparator.INCLUDED) {
                renderKeyValueSeparator(writer);
            } else {
                // objects can have separators omitted
                // TODO - should this be configurable?
                writer.append(' ');
            }

            renderNode(writer, node, indentLevel + 1);
        }
    }

    private static boolean isSimpleKey(final String keyName) {
        final int len = keyName.length();
        if (len == 0) {
            return false;
        }

        for (int i = 0; i < len; i++) {
            final char c = keyName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                continue;
            }
            return false;
        }
        return true;
    }

    private void renderList(final StringWriter writer, final List<? extends ConfigurationNode> list, final int indentLevel) {
        // TODO - lists are more complicated
        throw new IllegalStateException("TODO");
    }

    private void renderScalar(final StringWriter writer, final @Nullable Object value, final int indentLevel) {
        final String valueText = ConfigValueFactory.fromAnyRef(value).render(this.valueRenderOptions);
        writer.append(valueText);
        renderNewline(writer);
    }

    private void renderIndent(final StringWriter writer, final int indentLevel) {
        for (int i = 0; i < indentLevel; i++) {
            writer.append(this.computedIndent);
        }
    }

    private void renderNewline(final StringWriter writer) {
        writer.append(this.options.lineSeparator.getSeparator());
    }

    private void renderKeyValueSeparator(final StringWriter writer) {
        if (!this.computedSpaceLeftOfSep.isEmpty()) {
            writer.append(this.computedSpaceLeftOfSep);
        }
        writer.append(this.options.separatorCharacter.getSeparator());
        if (!this.computedSpaceRightOfSep.isEmpty()) {
            writer.append(this.computedSpaceRightOfSep);
        }
    }

    public static final class Options {

        private final SeparatorCharacter separatorCharacter;
        private final ObjectSeparator objectSeparator;
        private final StringQuoting stringQuoting;

        private final int spacesBeforeSeparator;
        private final int spacesAfterSeparator;

        private final IndentCharacter indentCharacter;
        private final int indent;

        private final CommentStyle commentStyle;
        private final LineSeparator lineSeparator;

        private Options(final Builder builder) {
            this.separatorCharacter = builder.separatorCharacter;
            this.objectSeparator = builder.objectSeparator;
            this.stringQuoting = builder.stringQuoting;
            this.spacesBeforeSeparator = builder.spacesBeforeSeparator;
            this.spacesAfterSeparator = builder.spacesAfterSeparator;
            this.indentCharacter = builder.indentCharacter;
            this.indent = builder.indent;
            this.commentStyle = builder.commentStyle;
            this.lineSeparator = builder.lineSeparator;
        }

        public static Options from(final Builder builder) {
            return new Options(builder);
        }

        public static Options defaults() {
            return Options.builder().build();
        }

        public static Builder builder() {
            return Builder.defaults();
        }

        public static Builder builder(final Options options) {
            return Builder.copyFrom(options);
        }

        public static final class Builder {

            SeparatorCharacter separatorCharacter = SeparatorCharacter.COLON;
            ObjectSeparator objectSeparator = ObjectSeparator.OMITTED;
            StringQuoting stringQuoting = StringQuoting.WHEN_REQUIRED;

            int spacesBeforeSeparator = 0;
            int spacesAfterSeparator = 1;

            IndentCharacter indentCharacter = IndentCharacter.SPACE;
            int indent = 4;

            CommentStyle commentStyle = CommentStyle.HASH;
            LineSeparator lineSeparator = LineSeparator.SYSTEM;

            private Builder() {
            }

            static Builder defaults() {
                return new Builder();
            }

            static Builder copyFrom(final Options options) {
                Builder builder = defaults();
                builder.separatorCharacter = options.separatorCharacter;
                builder.objectSeparator = options.objectSeparator;
                builder.stringQuoting = options.stringQuoting;
                builder.spacesBeforeSeparator = options.spacesBeforeSeparator;
                builder.spacesAfterSeparator = options.spacesAfterSeparator;
                builder.indentCharacter = options.indentCharacter;
                builder.indent = options.indent;
                builder.commentStyle = options.commentStyle;
                builder.lineSeparator = options.lineSeparator;
                return builder;
            }

            public Builder withSeparatorCharacter(final SeparatorCharacter separatorCharacter) {
                this.separatorCharacter = separatorCharacter;
                return this;
            }

            public Builder withObjectSeparator(final ObjectSeparator objectSeparator) {
                this.objectSeparator = objectSeparator;
                return this;
            }

            public Builder withStringQuoting(final StringQuoting stringQuoting) {
                this.stringQuoting = stringQuoting;
                return this;
            }

            public Builder withSeparatorSpacing(final int spacesBeforeSeparator, final int spacesAfterSeparator) {
                this.spacesBeforeSeparator = spacesBeforeSeparator;
                this.spacesAfterSeparator = spacesAfterSeparator;
                return this;
            }

            public Builder withIndent(final IndentCharacter indentCharacter, final int charactersPerIndent) {
                this.indentCharacter = indentCharacter;
                this.indent = charactersPerIndent;
                return this;
            }

            public Builder withCommentStyle(final CommentStyle commentStyle) {
                this.commentStyle = commentStyle;
                return this;
            }

            public Builder withLineSeparator(final LineSeparator lineSeparator) {
                this.lineSeparator = lineSeparator;
                return this;
            }

            public Options build() {
                return Options.from(this);
            }
        }

        public enum SeparatorCharacter {
            COLON(':'),
            EQUALS('=');

            private final char separator;

            SeparatorCharacter(final char separator) {
                this.separator = separator;
            }

            public char getSeparator() {
                return separator;
            }
        }

        public enum ObjectSeparator {
            OMITTED,
            INCLUDED
        }

        public enum StringQuoting {
            WHEN_REQUIRED,
            ALWAYS
        }

        public enum IndentCharacter {
            SPACE(' '),
            TAB('\t');

            private final char indentChar;

            IndentCharacter(final char indentChar) {
                this.indentChar = indentChar;
            }

            public char getIndentChar() {
                return indentChar;
            }
        }

        public enum CommentStyle {
            HASH("#"),
            SLASH("//");

            private final String commentPrefix;

            CommentStyle(final String commentPrefix) {
                this.commentPrefix = commentPrefix;
            }

            public String getCommentPrefix() {
                return commentPrefix;
            }
        }

        public enum LineSeparator {
            SYSTEM(System.lineSeparator()),
            LR("\n"),
            CRLF("\r\n");

            private final String separator;

            LineSeparator(final String separator) {
                this.separator = separator;
            }

            public String getSeparator() {
                return separator;
            }
        }
    }
}
