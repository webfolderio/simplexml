package xmlparser.xpath;

import static xmlparser.utils.Constants.SEGMENT_EXPRESSION;

/**
 * Adapted from xml-lif (https://github.com/liflab/xml-lif) by Sylvain Hallé
 */
public class TextSegment extends Segment {

	@Override
	public String toString()
	{
		return SEGMENT_EXPRESSION;
	}

}
