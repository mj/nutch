/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nutch.indexer.more;

import org.apache.tika.mime.MimeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.nutch.metadata.Metadata;

import org.apache.nutch.net.protocols.HttpDateFormat;
import org.apache.nutch.net.protocols.Response;

import org.apache.nutch.parse.Parse;

import org.apache.nutch.indexer.IndexingFilter;
import org.apache.nutch.indexer.IndexingException;
import org.apache.nutch.indexer.NutchDocument;

import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.Inlinks;
import org.apache.nutch.parse.ParseData;
import org.apache.nutch.util.MimeUtil;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;

import java.text.ParseException;
import java.text.SimpleDateFormat;

import java.util.Date;
import java.util.regex.*;


import org.apache.commons.lang.time.DateUtils;

/**
 * Add (or reset) a few metaData properties as respective fields
 * (if they are available), so that they can be displayed by more.jsp
 * (called by search.jsp).
 *
 * content-type is indexed to support query by type:
 * last-modifed is indexed to support query by date:
 *
 * Still need to make content-length searchable!
 *
 * @author John Xing
 */

public class MoreIndexingFilter implements IndexingFilter {
  public static final Logger LOG = LoggerFactory.getLogger(MoreIndexingFilter.class);

  /** A flag that tells if magic resolution must be performed */
  private boolean MAGIC;

  /** Get the MimeTypes resolver instance. */
  private MimeUtil MIME;

  public NutchDocument filter(NutchDocument doc, Parse parse, Text url, CrawlDatum datum, Inlinks inlinks)
    throws IndexingException {

    String url_s = url.toString();

    addTime(doc, parse.getData(), url_s, datum);
    addLength(doc, parse.getData(), url_s);
    addType(doc, parse.getData(), url_s);
    resetTitle(doc, parse.getData(), url_s);

    return doc;
  }

  // Add time related meta info.  Add last-modified if present.  Index date as
  // last-modified, or, if that's not present, use fetch time.
  private NutchDocument addTime(NutchDocument doc, ParseData data,
                           String url, CrawlDatum datum) {
    long time = -1;

    String lastModified = data.getMeta(Metadata.LAST_MODIFIED);
    if (lastModified != null) {                   // try parse last-modified
      time = getTime(lastModified,url);           // use as time
                                                  // store as string
      doc.add("lastModified", new Date(time));
    }

    if (time == -1) {                             // if no last-modified
      time = datum.getFetchTime();                // use fetch time
    }

    // un-stored, indexed and un-tokenized
    doc.add("date", new Date(time));

    return doc;
  }

  private long getTime(String date, String url) {
    long time = -1;
    try {
      time = HttpDateFormat.toLong(date);
    } catch (ParseException e) {
  // try to parse it as date in alternative format
  try {
      Date parsedDate = DateUtils.parseDate(date,
      new String [] {
          "EEE MMM dd HH:mm:ss yyyy",
          "EEE MMM dd HH:mm:ss yyyy zzz",
          "EEE MMM dd HH:mm:ss zzz yyyy",
          "EEE, MMM dd HH:mm:ss yyyy zzz",
          "EEE, dd MMM yyyy HH:mm:ss zzz",
          "EEE,dd MMM yyyy HH:mm:ss zzz",
          "EEE, dd MMM yyyy HH:mm:sszzz",
          "EEE, dd MMM yyyy HH:mm:ss",
          "EEE, dd-MMM-yy HH:mm:ss zzz",
          "yyyy/MM/dd HH:mm:ss.SSS zzz",
          "yyyy/MM/dd HH:mm:ss.SSS",
          "yyyy/MM/dd HH:mm:ss zzz",
          "yyyy/MM/dd",
          "yyyy.MM.dd HH:mm:ss",
          "yyyy-MM-dd HH:mm",
          "MMM dd yyyy HH:mm:ss. zzz",
          "MMM dd yyyy HH:mm:ss zzz",
          "dd.MM.yyyy HH:mm:ss zzz",
          "dd MM yyyy HH:mm:ss zzz",
          "dd.MM.yyyy; HH:mm:ss",
          "dd.MM.yyyy HH:mm:ss",
          "dd.MM.yyyy zzz",
          "yyyy-MM-dd'T'HH:mm:ss'Z'"
      });
      time = parsedDate.getTime();
            // if (LOG.isWarnEnabled()) {
      //   LOG.warn(url + ": parsed date: " + date +" to:"+time);
            // }
  } catch (Exception e2) {
            if (LOG.isWarnEnabled()) {
        LOG.warn(url + ": can't parse erroneous date: " + date);
            }
  }
    }
    return time;
  }

  // Add Content-Length
  private NutchDocument addLength(NutchDocument doc, ParseData data, String url) {
    String contentLength = data.getMeta(Response.CONTENT_LENGTH);

    if (contentLength != null)
      // NUTCH-1010 ContentLength not trimmed
      doc.add("contentLength", contentLength.trim());

    return doc;
  }

  /**
   * <p>
   * Add Content-Type and its primaryType and subType add contentType,
   * primaryType and subType to field "type" as un-stored, indexed and
   * un-tokenized, so that search results can be confined by contentType or its
   * primaryType or its subType.
   * </p>
   * <p>
   * For example, if contentType is application/vnd.ms-powerpoint, search can be
   * done with one of the following qualifiers
   * type:application/vnd.ms-powerpoint type:application type:vnd.ms-powerpoint
   * all case insensitive. The query filter is implemented in
   * {@link TypeQueryFilter}.
   * </p>
   *
   * @param doc
   * @param data
   * @param url
   * @return
   */
  private NutchDocument addType(NutchDocument doc, ParseData data, String url) {
    MimeType mimeType = null;
    String contentType = data.getMeta(Response.CONTENT_TYPE);
    if (contentType == null) {
      // Note by Jerome Charron on 20050415:
      // Content Type not solved by a previous plugin
      // Or unable to solve it... Trying to find it
      // Should be better to use the doc content too
      // (using MimeTypes.getMimeType(byte[], String), but I don't know
      // which field it is?
      // if (MAGIC) {
      //   contentType = MIME.getMimeType(url, content);
      // } else {
      //   contentType = MIME.getMimeType(url);
      // }
      mimeType = MIME.getMimeType(url);
    } else {
      mimeType = MIME.forName(MimeUtil.cleanMimeType(contentType));
    }

    // Checks if we solved the content-type.
    if (mimeType == null) {
      return doc;
    }

    contentType = mimeType.getName();

    doc.add("type", contentType);

    // Check if we need to split the content type in sub parts
    if (conf.getBoolean("moreIndexingFilter.indexMimeTypeParts", true)) {
      String[] parts = getParts(contentType.toString());

      for(String part: parts) {
        doc.add("type", part);
      }
    }

    // leave this for future improvement
    //MimeTypeParameterList parameterList = mimeType.getParameters()

    return doc;
  }


  /**
   * Utility method for splitting mime type into type and subtype.
   * @param mimeType
   * @return
   */
  static String[] getParts(String mimeType) {
    return mimeType.split("/");
  }

  // Reset title if we see non-standard HTTP header "Content-Disposition".
  // It's a good indication that content provider wants filename therein
  // be used as the title of this url.

  // Patterns used to extract filename from possible non-standard
  // HTTP header "Content-Disposition". Typically it looks like:
  // Content-Disposition: inline; filename="foo.ppt"
  private Configuration conf;

  static Pattern patterns[] = {null, null};

  static {
    try {
      // order here is important
      patterns[0] =
        Pattern.compile("\\bfilename=['\"](.+)['\"]");
      patterns[1] =
        Pattern.compile("\\bfilename=(\\S+)\\b");
    } catch (PatternSyntaxException e) {
      // just ignore
    }
  }

  private NutchDocument resetTitle(NutchDocument doc, ParseData data, String url) {
    String contentDisposition = data.getMeta(Metadata.CONTENT_DISPOSITION);
    if (contentDisposition == null)
      return doc;

    for (int i=0; i<patterns.length; i++) {
      Matcher matcher = patterns[i].matcher(contentDisposition);
      if (matcher.find()) {
        doc.add("title", matcher.group(1));
        break;
      }
    }

    return doc;
  }

  public void setConf(Configuration conf) {
    this.conf = conf;
    MIME = new MimeUtil(conf);
  }

  public Configuration getConf() {
    return this.conf;
  }

}
