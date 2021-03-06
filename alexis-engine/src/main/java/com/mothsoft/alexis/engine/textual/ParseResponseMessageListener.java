/*   Copyright 2012 Tim Garrett, Mothsoft LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mothsoft.alexis.engine.textual;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.commons.lang.time.StopWatch;
import org.apache.log4j.Logger;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.springframework.jms.listener.SessionAwareMessageListener;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.mothsoft.alexis.dao.DocumentDao;
import com.mothsoft.alexis.dao.TermDao;
import com.mothsoft.alexis.domain.AssociationType;
import com.mothsoft.alexis.domain.Document;
import com.mothsoft.alexis.domain.DocumentAssociation;
import com.mothsoft.alexis.domain.DocumentNamedEntity;
import com.mothsoft.alexis.domain.DocumentTerm;
import com.mothsoft.alexis.domain.ParsedContent;
import com.mothsoft.alexis.domain.PartOfSpeech;
import com.mothsoft.alexis.domain.Term;
import com.mothsoft.alexis.security.CurrentUserUtil;

public class ParseResponseMessageListener implements SessionAwareMessageListener<TextMessage> {

    private static final Logger logger = Logger.getLogger(ParseResponseMessageListener.class);

    private static final String DOCUMENT_ID = "DOCUMENT_ID";

    private static final Pattern PUNCT_PATTERN = Pattern.compile("\\p{Punct}");

    private DocumentDao documentDao;
    private TermDao termDao;
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    public void setDocumentDao(final DocumentDao documentDao) {
        this.documentDao = documentDao;
    }

    public void setTermDao(final TermDao termDao) {
        this.termDao = termDao;
    }

    public void setTransactionManager(final PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(this.transactionManager);
    }

    @Override
    public void onMessage(final TextMessage message, final Session session) throws JMSException {

        final long documentId = message.getLongProperty(DOCUMENT_ID);
        logger.info("Received response for document ID: " + documentId);

        final String xml = message.getText();

        try {
            final ParsedContent parsedContent = readResponse(xml);
            updateDocument(documentId, parsedContent);
        } catch (final IOException e) {
            final JMSException e2 = new JMSException(e.getMessage());
            e2.setLinkedException(e);
            throw e2;
        }
    }

    @SuppressWarnings("unchecked")
    private ParsedContent readResponse(final String xml) throws IOException {
        // turn the content into a ParsedContent complex object
        final Map<Term, Integer> termCountMap = new HashMap<Term, Integer>();
        final Map<String, DocumentAssociation> associationCountMap = new HashMap<String, DocumentAssociation>();
        final List<DocumentNamedEntity> entities = new ArrayList<DocumentNamedEntity>();

        final List<Node> sentenceNodes = new ArrayList<Node>();

        final SAXReader saxReader = new SAXReader();

        org.dom4j.Document document;
        try {
            document = saxReader.read(new StringReader(xml));
        } catch (DocumentException e) {
            throw new IOException(e);
        }

        sentenceNodes.addAll(document.selectNodes("/document/sentences/s"));

        int sentencePosition = 1;

        for (final Node ith : sentenceNodes) {
            parseSentence(sentencePosition++, (Element) ith, termCountMap, associationCountMap);
        }
        sentenceNodes.clear();

        final List<Node> nameNodes = new ArrayList<Node>();
        nameNodes.addAll(document.selectNodes("/document/names/name"));
        for (final Node node : nameNodes) {
            final Element element = (Element) node;
            final String countString = element.attributeValue("count");
            final Integer count = (countString == null ? 0 : Integer.valueOf(countString));
            final String name = element.getTextTrim();
            entities.add(new DocumentNamedEntity(name, count));
        }

        // count up all the terms in the document
        Integer documentTermCount = 0;
        for (final Term ith : termCountMap.keySet()) {
            documentTermCount += (termCountMap.get(ith));
        }

        // individual term count
        final List<DocumentTerm> documentTerms = new ArrayList<DocumentTerm>();
        for (final Term term : termCountMap.keySet()) {
            final Integer count = termCountMap.get(term);
            documentTerms.add(new DocumentTerm(term, count));
        }

        // sort the named entities by count
        Collections.sort(entities, new Comparator<DocumentNamedEntity>() {
            @Override
            public int compare(final DocumentNamedEntity e1, DocumentNamedEntity e2) {
                return -1 * e1.getCount().compareTo(e2.getCount());
            }
        });

        return new ParsedContent(associationCountMap.values(), documentTerms, entities, documentTermCount);
    }

    @SuppressWarnings("unchecked")
    private void parseSentence(final Integer position, final Element element, final Map<Term, Integer> termCountMap,
            Map<String, DocumentAssociation> associationMap) throws IOException {

        final List<Term> terms = new ArrayList<Term>();

        List<Node> words = element.selectNodes("words/word");
        for (final Node word : words) {
            final String pos = ((Element) word).attributeValue("pos");
            final String value = ((Element) word).getStringValue();

            final PartOfSpeech partOfSpeech;

            // check hints that this is punctuation
            if (pos.equals(value)) {
                partOfSpeech = PartOfSpeech.PUNCTUATION;
            } else if (PUNCT_PATTERN.matcher(pos).matches()) {
                partOfSpeech = PartOfSpeech.PUNCTUATION;
            } else {
                partOfSpeech = PartOfSpeech.parse(pos);
            }

            final Term term = findOrCreateTerm(value, partOfSpeech);
            terms.add(term);
            countTerm(term, termCountMap);
        }

        List<Node> deps = element.selectNodes("dependencies/dep");
        if (deps != null) {
            for (final Node ith : deps) {
                final Element dep = (Element) ith;
                String typeString = dep.attributeValue("type");

                if (typeString.equals(AssociationType.root.name())) {
                    continue;
                }

                final int aPos = Integer.valueOf(dep.element("governor").attribute("idx").getStringValue());
                final int bPos = Integer.valueOf(dep.element("dependent").attribute("idx").getStringValue());

                final Term a = terms.get(aPos - 1);
                final Term b = terms.get(bPos - 1);
                final AssociationType type = AssociationType.valueOf(typeString);
                final String key = String.format("%s:%s:%s", a.getValue(), b.getValue(), type.name());

                final DocumentAssociation association;
                if (associationMap.containsKey(key)) {
                    association = associationMap.get(key);
                    association.setAssociationCount(1 + association.getAssociationCount());
                } else {
                    association = new DocumentAssociation(a, b, type);
                    association.setAssociationCount(1 + association.getAssociationCount());
                    associationMap.put(key, association);
                }
            }
        }
    }

    private Term findOrCreateTerm(final String value, final PartOfSpeech partOfSpeech) {
        final int maxTries = 20;
        int retryCount = maxTries;

        Term term = null;
        while (term == null && retryCount > 0) {
            retryCount--;

            try {
                term = this.transactionTemplate.execute(new TransactionCallback<Term>() {
                    public Term doInTransaction(TransactionStatus status) {
                        try {
                            Term term = ParseResponseMessageListener.this.termDao.find(value, partOfSpeech);
                            if (term == null) {
                                term = new Term(value, partOfSpeech);
                                ParseResponseMessageListener.this.termDao.add(term);
                            }
                            return term;
                        } catch (JpaSystemException e) {
                            throw new TransactionSystemException(e.getLocalizedMessage());
                        }
                    }
                });
            } catch (TransactionException e) {
                if (retryCount == 0) {
                    logger.error("Retried transaction " + maxTries + " times; failed every time!" + e, e);
                    throw e;
                } else {
                    logger.warn("Experienced transaction failure: " + e.getLocalizedMessage() + "; will retry!");
                    continue;
                }
            }
        }

        return term;
    }

    private void countTerm(Term term, Map<Term, Integer> termCountMap) {
        Integer currentValue = 0;

        if (termCountMap.containsKey(term)) {
            currentValue = termCountMap.get(term);
        }

        termCountMap.put(term, currentValue + 1);
    }

    private void updateDocument(final Long documentId, final ParsedContent parsedContent) {
        logger.info("Beginning to update document: " + documentId);

        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {

            @Override
            protected void doInTransactionWithoutResult(final TransactionStatus txStatus) {
                try {
                    CurrentUserUtil.setSystemUserAuthentication();
                    final Document attachedDocument = ParseResponseMessageListener.this.documentDao.get(documentId);
                    attachedDocument.setParsedContent(parsedContent);
                } finally {
                    CurrentUserUtil.clearAuthentication();
                }
            }

        });

        stopWatch.stop();
        logger.info("Document update for ID: " + documentId + " took: " + stopWatch.toString());
    }

}
