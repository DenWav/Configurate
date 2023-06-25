/*
 * Configurate
 * Copyright (C) zml and Configurate contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spongepowered.configurate.hocon;

import static org.spongepowered.configurate.loader.AbstractConfigurationLoader.CONFIGURATE_LINE_PATTERN;

import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.impl.ConfigImplUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.util.Strings;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class HoconRenderer {

    private final Options options;

    private final String computedIndent;
    private final String computedSpaceLeftOfSep;
    private final String computedSpaceRightOfSep;

    private final ConfigRenderOptions valueRenderOptions;

    private HoconRenderer(final Options options) {
        this.options = options;
        final String indentChar = Character.toString(this.options.indentCharacter.indentChar());
        this.computedIndent = Strings.repeat(indentChar, this.options.indent);

        this.computedSpaceLeftOfSep = Strings.repeat(" ", this.options.spacesBeforeSeparator);
        this.computedSpaceRightOfSep = Strings.repeat(" ", this.options.spacesAfterSeparator);

        this.valueRenderOptions = quotingRenderOptions(this.options.stringQuoting);
    }

    private static ConfigRenderOptions quotingRenderOptions(final Options.Quoting quoting) {
        if (quoting == Options.Quoting.ALWAYS) {
            // concise defaults to `json` which will always quote strings
            return ConfigRenderOptions.concise();
        } else if (quoting == Options.Quoting.WHEN_REQUIRED) {
            return ConfigRenderOptions.concise().setJson(false);
        } else {
            throw new IllegalStateException("quoting value unexpected: " + quoting);
        }
    }

    static HoconRenderer from(final Options options) {
        return new HoconRenderer(options);
    }

    String renderNode(final ConfigurationNode node) throws ConfigurateException {
        final StringBuilder sb = new StringBuilder();
        this.renderNode(sb, node, 0);
        return sb.toString();
    }

    private void renderNode(final StringBuilder sb, final ConfigurationNode node, final int indentLevel) throws ConfigurateException {
        if (node.isMap()) {
            this.renderMap(sb, node.childrenMap(), indentLevel);
        } else if (node.isList()) {
            this.renderList(sb, node.childrenList(), indentLevel);
        } else {
            this.renderScalar(sb, node.rawScalar(), indentLevel);
        }
    }

    private void renderComments(final StringBuilder sb, final ConfigurationNode node, final int indentLevel) {
        if (!(node instanceof CommentedConfigurationNode)) {
            return;
        }

        final @Nullable String comment = ((CommentedConfigurationNode) node).comment();
        if (comment == null) {
            return;
        }

        for (String line : CONFIGURATE_LINE_PATTERN.split(comment)) {
            this.renderIndent(sb, indentLevel);
            sb.append(this.options.commentStyle.commentPrefix());
            sb.append(' ');
            sb.append(line);
            renderNewline(sb);
        }
    }

    private void renderMap(
        final StringBuilder sb,
        final Map<Object, ? extends ConfigurationNode> map,
        final int indentLevel
    ) throws ConfigurateException {
        if (indentLevel > 0) {
            // Only open maps when we aren't dealing with the root node
            sb.append('{');
            renderNewline(sb);
        }

        for (final Map.Entry<Object, ? extends ConfigurationNode> entry : map.entrySet()) {
            final ConfigurationNode node = entry.getValue();

            renderComments(sb, node, indentLevel);
            renderIndent(sb, indentLevel);

            final String key = String.valueOf(entry.getKey());
            if (!isSimpleKey(key) || this.options.keyQuoting == Options.Quoting.ALWAYS) {
                sb.append(ConfigImplUtil.renderJsonString(key));
            } else {
                sb.append(key);
            }

            if (!node.isMap() || this.options.objectSeparator == Options.ObjectSeparator.INCLUDED) {
                renderKeyValueSeparator(sb);
            } else {
                // objects can have separators omitted
                // TODO - should this be configurable?
                sb.append(' ');
            }

            renderNode(sb, node, indentLevel + 1);
            renderNewline(sb);
        }

        if (indentLevel > 0) {
            // close map, again only when we aren't the root node
            // indentLevel - 1 because it's the closing brace
            renderIndent(sb, indentLevel - 1);
            sb.append('}');
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

    private void renderList(final StringBuilder sb, final List<? extends ConfigurationNode> list, final int indentLevel) throws ConfigurateException {
        // First, check if there's anything in this list which would prevent us from writing it in 1 line
        // Anything that isn't a scalar will result in the whole list being written out with each value on its own line
        // Comments on any nodes also necessitates individual lines
        boolean inline = true;
        for (final ConfigurationNode node : list) {
            if (node.isMap() || node.isList()) {
                inline = false;
                break;
            }
            if (node instanceof CommentedConfigurationNode) {
                if (((CommentedConfigurationNode) node).comment() != null) {
                    inline = false;
                    break;
                }
            }
        }

        if (inline) {
            final int longLineLength = 80;
            // inline is possible, but we don't want to write out lists that are too long
            int length = 0;
            for (final ConfigurationNode node : list) {
                final String valueText = ConfigValueFactory.fromAnyRef(node.rawScalar()).render(this.valueRenderOptions);
                length += valueText.length() + 1;
                if (length >= longLineLength) {
                    break;
                }
            }
            if (length >= longLineLength) {
                inline = false;
            }
        }

        sb.append('[');

        final Iterator<? extends ConfigurationNode> iter = list.iterator();
        while (iter.hasNext()) {
            final ConfigurationNode node = iter.next();
            if (inline) {
                sb.append(' ');
                renderNode(sb, node, 0);
            } else {
                renderNewline(sb);
                renderIndent(sb, indentLevel);
                renderNode(sb, node, indentLevel + 1);
            }
            if (iter.hasNext()) {
                sb.append(',');
            }
        }

        if (inline) {
            sb.append(" ]");
        } else {
            renderNewline(sb);
            renderIndent(sb, indentLevel - 1);
            sb.append(']');
        }
    }

    private void renderScalar(final StringBuilder sb, final @Nullable Object value, final int indentLevel) {
        final String valueText = ConfigValueFactory.fromAnyRef(value).render(this.valueRenderOptions);
        sb.append(valueText);
    }

    private void renderIndent(final StringBuilder sb, final int indentLevel) {
        for (int i = 0; i < indentLevel; i++) {
            sb.append(this.computedIndent);
        }
    }

    private void renderNewline(final StringBuilder sb) {
        sb.append(this.options.lineSeparator.separator());
    }

    private void renderKeyValueSeparator(final StringBuilder sb) {
        if (!this.computedSpaceLeftOfSep.isEmpty()) {
            sb.append(this.computedSpaceLeftOfSep);
        }
        sb.append(this.options.separatorCharacter.separator());
        if (!this.computedSpaceRightOfSep.isEmpty()) {
            sb.append(this.computedSpaceRightOfSep);
        }
    }

    public static final class Options {

        private final SeparatorCharacter separatorCharacter;
        private final ObjectSeparator objectSeparator;
        private final Quoting stringQuoting;
        private final Quoting keyQuoting;

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
            this.keyQuoting = builder.keyQuoting;
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
            Quoting stringQuoting = Quoting.WHEN_REQUIRED;
            Quoting keyQuoting = Quoting.WHEN_REQUIRED;

            int spacesBeforeSeparator;
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
                final Builder builder = defaults();
                builder.separatorCharacter = options.separatorCharacter;
                builder.objectSeparator = options.objectSeparator;
                builder.stringQuoting = options.stringQuoting;
                builder.keyQuoting = options.keyQuoting;
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

            public Builder withStringQuoting(final Quoting stringQuoting) {
                this.stringQuoting = stringQuoting;
                return this;
            }

            public Builder withKeyQuoting(final Quoting keyQuoting) {
                this.keyQuoting = keyQuoting;
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

        public SeparatorCharacter separatorCharacter() {
            return this.separatorCharacter;
        }

        public ObjectSeparator objectSeparator() {
            return this.objectSeparator;
        }

        public Quoting stringQuoting() {
            return this.stringQuoting;
        }

        public Quoting keyQuoting() {
            return this.keyQuoting;
        }

        public int spacesBeforeSeparator() {
            return this.spacesBeforeSeparator;
        }

        public int spacesAfterSeparator() {
            return this.spacesAfterSeparator;
        }

        public IndentCharacter indentCharacter() {
            return this.indentCharacter;
        }

        public int indent() {
            return this.indent;
        }

        public CommentStyle commentStyle() {
            return this.commentStyle;
        }

        public LineSeparator lineSeparator() {
            return this.lineSeparator;
        }

        public enum SeparatorCharacter {
            COLON(':'),
            EQUALS('=');

            private final char separator;

            SeparatorCharacter(final char separator) {
                this.separator = separator;
            }

            public char separator() {
                return this.separator;
            }
        }

        public enum ObjectSeparator {
            OMITTED,
            INCLUDED
        }

        public enum Quoting {
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

            public char indentChar() {
                return this.indentChar;
            }
        }

        public enum CommentStyle {
            HASH("#"),
            DOUBLE_SLASH("//");

            private final String commentPrefix;

            CommentStyle(final String commentPrefix) {
                this.commentPrefix = commentPrefix;
            }

            public String commentPrefix() {
                return this.commentPrefix;
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

            public String separator() {
                return this.separator;
            }
        }

    }

}
