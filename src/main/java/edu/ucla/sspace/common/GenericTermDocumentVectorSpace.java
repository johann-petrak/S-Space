/*
 * Copyright 2010 Keith Stevens
 *
 * This file is part of the S-Space package and is covered under the terms and
 * conditions therein.
 *
 * The S-Space package is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation and distributed hereunder to you.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND NO REPRESENTATIONS OR WARRANTIES,
 * EXPRESS OR IMPLIED ARE MADE.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, WE MAKE
 * NO REPRESENTATIONS OR WARRANTIES OF MERCHANT- ABILITY OR FITNESS FOR ANY
 * PARTICULAR PURPOSE OR THAT THE USE OF THE LICENSED SOFTWARE OR DOCUMENTATION
 * WILL NOT INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR OTHER
 * RIGHTS.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package edu.ucla.sspace.common;

import edu.ucla.sspace.basis.BasisMapping;
import edu.ucla.sspace.basis.StringBasisMapping;

import edu.ucla.sspace.matrix.Matrices;
import edu.ucla.sspace.matrix.Matrix;
import edu.ucla.sspace.matrix.MatrixFile;
import edu.ucla.sspace.matrix.MatrixBuilder;
import edu.ucla.sspace.matrix.MatrixIO;
import edu.ucla.sspace.matrix.MatrixIO.Format;
import edu.ucla.sspace.matrix.SVD;
import edu.ucla.sspace.matrix.Transform;

import edu.ucla.sspace.text.IteratorFactory;

import edu.ucla.sspace.util.Counter;
import edu.ucla.sspace.util.LoggerUtil;
import edu.ucla.sspace.util.ObjectCounter;
import edu.ucla.sspace.util.SparseArray;
import edu.ucla.sspace.util.SparseIntHashArray;


import edu.ucla.sspace.vector.DoubleVector;
import edu.ucla.sspace.vector.Vector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOError;
import java.io.IOException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.logging.Logger;


/**
 * This base class centralizes much of the common text processing needed for
 * term-document based {@link SemanticSpace}s.  It processes a document by
 * tokenizing all of the provided text and counting the term occurrences within
 * the document.  Each column in these spaces represent a document, and the
 * column values initially represent the number of occurrences for each word.
 * After all documents are processed, the word space can be modified with one of
 * the many {@link Matrix} {@link Transform} classes.  The transform, if
 * provided, will be used to rescore each term document occurrence count.
 * Typically, this reweighting is typically done to increase the score for
 * important and distinguishing terms while less salient terms, such as stop
 * words, are given a lower score.  After calling {@link
 * #processSpace(Transform) processSpace}, sub classes should call assign thei
 * final data matrix to {@code wordSpace}.  This final matrix should maintain
 * the same row ordering, but the column ordering and dimensionality can be
 * modified in any way.
 *
 * <p>
 *
 * This class is thread-safe for concurrent calls of {@link
 * #processDocument(BufferedReader) processDocument}.  Once {@link
 * #processSpace(Transform) processSpace} has been called, no further calls to
 * {@link #processDocument(BufferedReader) processDocument} should be made.
 * This implementation does not support access to the semantic vectors until
 * after {@link #processSpace(Properties) processSpace} has been called.
 *
 * @see Transform
 * @see SVD
 * 
 * @author Keith Stevens
 */
public abstract class GenericTermDocumentVectorSpace 
        implements SemanticSpace, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    protected static final Logger LOG = 
        Logger.getLogger(GenericTermDocumentVectorSpace.class.getName());

    /**
     * A mapping from a word to the row index in the that word-document matrix
     * that contains occurrence counts for that word.
     */
    protected final BasisMapping<String, String> termToIndex;

    /**
     * The counter for recording the current number of documents observed.
     * Subclasses can use this for any reporting.
     */
    protected final AtomicInteger documentCounter;

    /**
     * The builder used to construct the term-document matrix as new documents
     * are processed.
     */
    private transient MatrixBuilder termDocumentMatrixBuilder;

    /**
     * If true, the first token in each document is considered to be a document
     * header.
     */
    private final boolean readHeaderToken;

    /**
     * The word space of the term document based word space model.  If the word
     * space is reduced, it is the left factor matrix of the SVD of the
     * word-document matrix.  This matrix is only available after the {@link
     * #processSpace(Transform) processSpace}
     * method has been called.
     */
    protected Matrix wordSpace;

    /**
     * Constructs the {@code GenericTermDocumentVectorSpace}.
     *
     * @throws IOException if this instance encounters any errors when creatng
     *         the backing array files required for processing
     */
    public GenericTermDocumentVectorSpace() throws IOException {
        this(false,new StringBasisMapping(),Matrices.getMatrixBuilderForSVD());
    }

    /**
     * Constructs the {@code GenericTermDocumentVectorSpace} using the provided
     * objects for processing.
     *
     * @param readHeaderToken If true, the first token of each document will be
     *        read and passed to {@link #handleDocumentHeader(int, String)
     *        handleDocumentHeader}, which by default discards the header.
     * @param termToIndex The {@link BasisMapping} used to map strings to
     *        indices.
     * @param termDocumentMatrixBuilder The {@link MatrixBuilder} used to write
     *        document vectors to disk which later get processed in {@link
     *        #processSpace(Properties) processSpace}.
     *
     * @throws IOException if this instance encounters any errors when creatng
     *         the backing array files required for processing
     */
    public GenericTermDocumentVectorSpace(
            boolean readHeaderToken,
            BasisMapping<String, String> termToIndex,
            MatrixBuilder termDocumentMatrixBuilder) throws IOException {
        this.readHeaderToken = readHeaderToken;
        this.termToIndex = termToIndex;
        documentCounter = new AtomicInteger(0);

        this.termDocumentMatrixBuilder = termDocumentMatrixBuilder;

        System.out.println("Saving matrix using " + termDocumentMatrixBuilder);

        wordSpace = null;
    }   

    /**
     * Tokenizes the document using the {@link IteratorFactory} and updates the
     * term-document frequency counts.
     *
     * <p>
     *
     * This method is thread-safe and may be called in parallel with separate
     * documents to speed up overall processing time.
     *
     * @param document {@inheritDoc}
     */
    public void processDocument(BufferedReader document) throws IOException {
        // Create a mapping for each term that is seen in the document to the
        // number of times it has been seen.  This mapping would more elegantly
        // be a SparseArray<Integer> however, the length of the sparse array
        // isn't known ahead of time, which prevents it being used by the
        // MatrixBuilder.  Note that the SparseArray implementation would also
        // incur an additional performance hit since each word would have to be
        // converted to its index form for each occurrence, which results in a
        // double Map look-up.
        Counter<String> termCounts = new ObjectCounter<String>();
        Iterator<String> documentTokens = IteratorFactory.tokenize(document);

        // Increaes the count of documents observed so far.
        int docCount = documentCounter.getAndAdd(1);

        // If the first token is to be interpreted as a document header read it.
        if (readHeaderToken)
            handleDocumentHeader(docCount, documentTokens.next());

        // If the document is empty, skip it
        if (!documentTokens.hasNext())
            return;
        
        // For each word in the text document, keep a count of how many times it
        // has occurred
        while (documentTokens.hasNext()) {
            String word = documentTokens.next();
            
            // Skip added empty tokens for words that have been filtered out
            if (word.equals(IteratorFactory.EMPTY_TOKEN))
                continue;
            
            // Add the term to the total list of terms to ensure it has a proper
            // index.  If the term was already added, this method is a no-op
            termToIndex.getDimension(word);

            termCounts.count(word);
        }

        document.close();
        System.out.printf("Saw %d terms, %d unique%n", termCounts.sum(), termCounts.size());
        
        // Check that we actually loaded in some terms before we increase the
        // documentIndex. This is done after increasing the document count since
        // some configurations may need the document order preserved, for
        // example, if each document corresponds to some cluster assignment.
        if (termCounts.size() == 0)
            return;

        // Get the total number of terms encountered so far, including any new
        // unique terms found in the most recent document
        int totalNumberOfUniqueWords = termToIndex.numDimensions();

        // Convert the Map count to a SparseArray
        SparseArray<Integer> documentColumn = 
            new SparseIntHashArray(totalNumberOfUniqueWords);
        for (Map.Entry<String,Integer> e : termCounts)
            documentColumn.set(
                    termToIndex.getDimension(e.getKey()), e.getValue());

        System.out.println(this + " processing doc " + documentColumn);

        // Update the term-document matrix with the results of processing the
        // document.
        termDocumentMatrixBuilder.addColumn(documentColumn);
    }

    /**
     * Returns the vector corresponding to a document processed by this space,
     * if available (optional operation).  Vector values represent the word
     * frequencies that have been transformed according to the {@link Transform}
     * instances provided to {@link #processSpace(Properites)}.
     *
     * <p>By default, the document vectors are unavailable and calling this
     * method will throw an {@link UnsupportedOperationException}.  However,
     * subclasses may override this method to provide the functionality.
     * Callers may test to see whether a document vector is available by calling
     * {@link #documentSpaceSize()}, which will return a positive value if
     * vectors are available.
     *
     * @param documentNumber the number of the document according to when it was
     *        processed
     *
     * @return a vector representing the semantics of the document in the
     *         document space.
     *
     * @throws IllegalArgumentException If the document number is out of range.
     * @throws IllegalStateException If the document space has not been retained
     *         or if {@link #processSpace(Properties)} has not been called yet
     *         to finalize this space.
     */
    public Vector getDocumentVector(int documentNumber) {
        throw new UnsupportedOperationException(
            "Getting the document vector is not supported by this class.");
    }

    /**
     * Returns the number of documents processed by this instance if the
     * document space has been retained or zero if no document vectors are
     * available.  Subclasses supporting document vectors should override this
     * method to indicate that document vectors are available.
     *
     * @return the number of documents processed if the document space has been
     *         retained or zero if no document vectors are available.
     */
    public int documentSpaceSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public Set<String> getWords() {
        return Collections.unmodifiableSet(termToIndex.keySet());
    }

    /**
     * {@inheritDoc}
     */
    public Vector getVector(String word) {
        // determine the index for the word
        int index = termToIndex.getDimension(word);
        
        return (index < 0) ? null : wordSpace.getRowVector(index);
    }

    /**
     * {@inheritDoc}
     */
    public int getVectorLength() {
        return wordSpace.columns();
    }

    /**
     * Processes the {@link GenericTermDocumentVectorSpace} with the provided
     * {@link Transform} if it is not {@code null} as a {@link MatrixFile}.
     * Otherwise, the raw term document counts are returned.  Sub classes must
     * call this in order to access the term document counts before doing any
     * other processing.
     *
     * @param transform A matrix transform used to rescale the original raw
     *        document counts.  If {@code null} no transform is done.
     */
    protected MatrixFile processSpace(Transform transform) {
        try {
            // first ensure that we are no longer writing to the matrix
            termDocumentMatrixBuilder.finish();

            // Get the finished matrix file from the builder
            File termDocumentMatrix = termDocumentMatrixBuilder.getFile();

            // If a transform was specified, perform the matrix transform.
            if (transform != null) {
                LoggerUtil.info(LOG, "performing %s transform", transform);

                LoggerUtil.verbose(
                        LOG,"stored term-document matrix in format %s at %s",
                        termDocumentMatrixBuilder.getMatrixFormat(),
                        termDocumentMatrix.getAbsolutePath());

                // Convert the raw term counts using the specified transform
                termDocumentMatrix = transform.transform(
                        termDocumentMatrix, 
                        termDocumentMatrixBuilder.getMatrixFormat());

                LoggerUtil.verbose(
                        LOG, "transformed matrix to %s",
                        termDocumentMatrix.getAbsolutePath());
            }

            return new MatrixFile(
                    termDocumentMatrix, 
                    termDocumentMatrixBuilder.getMatrixFormat());
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }
    }

    /**
     * Subclasses should override this method if they need to utilize a header
     * token for each document.  Implementations of this method <b>must</b> be
     * thread safe.  The default action is a no-op.
     *
     * @param docIndex The document id assigned to the current document
     * @param documentName The name of the current document.
     */
    protected void handleDocumentHeader(int docIndex, String header) {
    }
}
