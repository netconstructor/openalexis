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
package com.mothsoft.alexis.service.impl;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.mothsoft.alexis.dao.DocumentDao;
import com.mothsoft.alexis.domain.DataRange;
import com.mothsoft.alexis.domain.Document;
import com.mothsoft.alexis.domain.DocumentScore;
import com.mothsoft.alexis.domain.DocumentState;
import com.mothsoft.alexis.domain.Graph;
import com.mothsoft.alexis.domain.ImportantNamedEntity;
import com.mothsoft.alexis.domain.ImportantTerm;
import com.mothsoft.alexis.domain.SortOrder;
import com.mothsoft.alexis.domain.TopicDocument;
import com.mothsoft.alexis.engine.textual.DocumentFeatureContext;
import com.mothsoft.alexis.engine.textual.DocumentFeatures;
import com.mothsoft.alexis.security.CurrentUserUtil;
import com.mothsoft.alexis.service.DocumentService;

@Transactional
public class DocumentServiceImpl implements DocumentService {

    // dependencies
    private DocumentDao documentDao;

    public DocumentServiceImpl() {
        // default constructor
    }

    public final void setDocumentDao(final DocumentDao documentDao) {
        this.documentDao = documentDao;
    }

    public Document getDocument(Long id) {
        // FIXME - add security check
        final Document document = this.documentDao.get(id);
        return document;
    }

    public DataRange<Document> listDocumentsByOwner(Long userId, int first, int count) {
        return this.documentDao.listDocumentsByOwner(userId, first, count);
    }

    public List<ImportantTerm> getImportantTerms(Long userId, Timestamp startDate, Timestamp endDate, int count,
            boolean filterStopWords) {
        CurrentUserUtil.assertAuthenticatedUserOrAdminOrSystem(userId);
        return this.documentDao.getImportantTerms(userId, startDate, endDate, count, filterStopWords);
    }

    public List<ImportantTerm> getImportantTerms(Long documentId, int howMany, boolean filterStopWords) {
        return this.documentDao.getImportantTerms(documentId, howMany, filterStopWords);
    }

    public List<TopicDocument> getTopicDocuments(Long documentId) {
        return this.documentDao.getTopicDocuments(documentId);
    }

    public DataRange<Document> listDocumentsInTopicsByOwner(Long userId, int firstRecord, int numberOfRecords) {
        return this.documentDao.listDocumentsInTopicsByOwner(userId, firstRecord, numberOfRecords);
    }

    public List<Document> listTopDocuments(Long userId, final Date startDate, final Date endDate, int count) {
        return this.documentDao.listTopDocuments(userId, startDate, endDate, count);
    }

    public DataRange<DocumentScore> searchByOwnerAndExpression(Long userId, String query, SortOrder sortOrder,
            int first, int count) {
        final Date startDate = null; /* default */
        final Date endDate = null; /* default */
        return this.searchByOwnerAndExpression(userId, query, sortOrder, startDate, endDate, first, count);
    }

    public DataRange<DocumentScore> searchByOwnerAndExpression(Long userId, String query, SortOrder sortOrder,
            Date startDate, Date endDate, int first, int count) {
        return this.documentDao.searchByOwnerAndExpression(userId, query, sortOrder, startDate, endDate, first, count);
    }

    public DataRange<DocumentScore> searchByOwnerAndStateAndExpression(Long userId, DocumentState state, String query,
            int first, int count) {
        final Date startDate = null; /* default */
        final Date endDate = null; /* default */
        return this.documentDao.searchByOwnerAndStateAndExpression(userId, state, query, startDate, endDate, first,
                count);
    }

    public DataRange<DocumentScore> searchByOwnerAndStateAndExpression(Long userId, DocumentState state, String query,
            Date startDate, Date endDate, int first, int count) {
        return this.documentDao.searchByOwnerAndStateAndExpression(userId, state, query, startDate, endDate, first,
                count);
    }

    public Graph getRelatedTerms(final String queryString, final Long userId, final int howMany) {
        return this.documentDao.getRelatedTerms(queryString, userId, howMany);
    }

    public List<ImportantNamedEntity> getImportantNamedEntities(final Long userId, final Date startDate,
            final Date endDate, final int howMany) {
        return this.documentDao.getImportantNamedEntities(userId, startDate, endDate, howMany);
    }

    @Override
    public List<ImportantNamedEntity> getImportantNamedEntitiesForDocument(Long documentId, int howMany) {
        return this.documentDao.getImportantNamedEntitiesForDocument(documentId, howMany);
    }

    @Override
    public Double getSimilarity(Long aId, Long bId) {
        final Document a = this.getDocument(aId);
        final Document b = this.getDocument(bId);

        final DocumentFeatureContext dfc = new DocumentFeatureContext();
        final DocumentFeatures dfa = new DocumentFeatures(a, dfc);
        final DocumentFeatures dfb = new DocumentFeatures(b, dfc);

        return dfa.cosineSimilarity(dfb);
    }

}
