package simplexml;

import simplexml.model.*;
import simplexml.utils.Accessors.AccessDeserializers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static simplexml.utils.Constants.*;
import static simplexml.utils.Functions.*;

public interface XmlReader extends AccessDeserializers {

    default <T> T fromXml(final String input, final Class<T> clazz) throws IOException {
        return domToObject(fromXml(input), clazz);
    }
    default <T> T fromXml(final InputStream in, final Class<T> clazz) throws IOException {
        return domToObject(fromXml(in), clazz);
    }
    default ElementNode fromXml(final String input) throws IOException {
        return fromXml(new ByteArrayInputStream(input.getBytes(UTF_8)));
    }
    default ElementNode fromXml(final InputStream stream) throws IOException {
        return parse(new InputStreamReader(stream, UTF_8));
    }

    default <T> T domToObject(final ElementNode node, final Class<T> clazz) {
        try {
            final T o = newObject(clazz);

            for (final Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                final ObjectDeserializer conv = getDeserializer(f.getType());

                if (f.isAnnotationPresent(XmlTextNode.class)) {
                    if (conv != null) f.set(o, conv.convert(node.text));
                    continue;
                }

                final String name = toName(f);

                if (f.isAnnotationPresent(XmlAttribute.class)) {
                    if (conv == null) continue;
                    final String value = node.attributes.get(name);
                    if (value == null) continue;
                    f.set(o, conv.convert(value));
                    continue;
                }

                final Class<?> type = f.getType();
                if (Set.class.isAssignableFrom(type)) {
                    final Set<Object> set = new HashSet<>();
                    f.set(o, set);

                    final Class<?> elementType = getClassOfCollection(f);
                    final ObjectDeserializer elementConv = getDeserializer(elementType);

                    for (final ElementNode n : node.children) {
                        if (n.name.equals(name)) {
                            if (elementConv == null)
                                set.add(domToObject(n, elementType));
                            else
                                set.add(conv.convert(n.text));
                        }
                    }
                    continue;
                }
                if (List.class.isAssignableFrom(type)) {
                    final List<Object> list = new LinkedList<>();
                    f.set(o, list);

                    final Class<?> elementType = getClassOfCollection(f);
                    final ObjectDeserializer elementConv = getDeserializer(elementType);

                    for (final ElementNode n : node.children) {
                        if (n.name.equals(name)) {
                            if (elementConv == null)
                                list.add(domToObject(n, elementType));
                            else
                                list.add(conv.convert(n.text));
                        }
                    }
                    continue;
                }

                final String value = node.attributes.get(name);
                if (value != null) {
                    if (conv != null) f.set(o, conv.convert(value));
                    continue;
                }

                final ElementNode child = getNodeForName(name, node.children);
                if (child != null) {
                    if (conv == null) {
                        f.set(o, domToObject(child, f.getType()));
                    } else {
                        f.set(o, conv.convert(child.text));
                    }
                }
            }

            return o;
        } catch ( IllegalAccessException | SecurityException | IllegalArgumentException e) {
            return null;
        }
    }
    
    static Class<?> getClassOfCollection(final Field f) {
        final ParameterizedType stringListType = (ParameterizedType) f.getGenericType();
        return (Class<?>) stringListType.getActualTypeArguments()[0];
    }
    
    static ElementNode getNodeForName(final String name, final List<ElementNode> nodes) {
        for (final ElementNode n : nodes) {
            if (n.name.equals(name))
                return n;
        }
        return null;
    }
    
    static ElementNode parse(final InputStreamReader in) throws IOException {
        final EventParser p = new EventParser();

        String str;
        while ((str = readLine(in, XML_TAG_START)) != null) {
            if (!str.isEmpty()) p.someText(unescapeXml(str.trim()));

            str = readLine(in, XML_TAG_END).trim();
            if (str.charAt(0) == XML_PROLOG) continue;

            if (str.charAt(0) == XML_SELF_CLOSING) p.endNode();
            else {
                final String name = getNameOfTag(str);
                if (str.length() == name.length()) {
                    p.startNode(str, new HashMap<>());
                    continue;
                }

                final int beginAttr = name.length();
                final int end = str.length();
                if (str.endsWith(FORWARD_SLASH)) {
                    p.startNode(name, parseAttributes(str.substring(beginAttr, end-1)));
                    p.endNode();
                } else {
                    p.startNode(name, parseAttributes(str.substring(beginAttr+1, end)));
                }
            }
        }

        return p.getRoot();
    }

    static String readLine(final InputStreamReader in, final char end) throws IOException {
        final List<Character> chars = new LinkedList<>();
        int data;
        while ((data = in.read()) != -1) {
            if (data == end) break;
            chars.add((char) data);
        }
        if (data == -1) return null;

        char[] value = new char[chars.size()];
        int i = 0;
        for (final Character c : chars) value[i++] = c;
        return new String(value);
    }

    static String getNameOfTag(final String tag) {
        int offset = 0;
        for (; offset < tag.length(); offset++) {
            if (tag.charAt(offset) == CHAR_SPACE || tag.charAt(offset) == CHAR_FORWARD_SLASH)
                break;
        }
        return tag.substring(0, offset);
    }

    static HashMap<String, String> parseAttributes(String input) {
        final HashMap<String, String> attributes = new HashMap<>();

        while (!input.isEmpty()) {
            int startName = indexOfNonWhitespaceChar(input, 0);
            if (startName == -1) break;
            int equals = input.indexOf(CHAR_EQUALS, startName+1);
            if (equals == -1) break;

            final String name = input.substring(startName, equals).trim();
            input = input.substring(equals+1);

            int startValue = indexOfNonWhitespaceChar(input, 0);
            if (startValue == -1) break;

            int endValue; final String value;
            if (input.charAt(startValue) == CHAR_DOUBLE_QUOTE) {
                startValue++;
                endValue = input.indexOf(CHAR_DOUBLE_QUOTE, startValue);
                if (endValue == -1) endValue = input.length()-1;
                value = input.substring(startValue, endValue).trim();
            } else {
                endValue = indexOfWhitespaceChar(input, startValue+1);
                if (endValue == -1) endValue = input.length()-1;
                value = input.substring(startValue, endValue+1).trim();
            }

            input = input.substring(endValue+1);

            attributes.put(name, unescapeXml(value));
        }

        return attributes;
    }

    static int indexOfNonWhitespaceChar(final String input, int offset) {
        for (int i = offset; i < input.length(); i++) {
            char at = input.charAt(i);
            if (at == ' ' || at == '\t' || at == '\n' || at == '\r') continue;
            return i;
        }
        return -1;
    }
    static int indexOfWhitespaceChar(final String input, int offset) {
        for (int i = offset; i < input.length(); i++) {
            char at = input.charAt(i);
            if (at == ' ' || at == '\t' || at == '\n' || at == '\r') return i;
        }
        return -1;
    }
}
