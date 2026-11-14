package org.peekaboot.backend.tracing.tags;

/** The keys Peekaboot's bind-parameter capture puts on a query span. */
public final class QueryParameterTags {

    /**
     * The bind parameters Peekaboot's own capture records on a query span: a JSON array of
     * parameter sets, one per execution of a batch, each an array of SQL literals. A set cut at
     * the character limit ends with a SQL comment counting the parameters it left out.
     */
    public static final String PARAMETERS_TAG = "peekaboot.query.parameters";

    /**
     * How many parameter sets of a batch past the set or character limit {@link #PARAMETERS_TAG}
     * leaves out; absent when it left out none.
     */
    public static final String OMITTED_PARAMETER_SETS_TAG = "peekaboot.query.omitted-parameter-sets";

    private QueryParameterTags() {}
}
