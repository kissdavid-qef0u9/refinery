package tools.refinery.store.query.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.InputMismatchException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.viatra.query.runtime.api.GenericQuerySpecification;
import org.eclipse.viatra.query.runtime.api.ViatraQueryEngine;
import org.eclipse.viatra.query.runtime.api.scope.QueryScope;
import org.eclipse.viatra.query.runtime.matchers.backend.QueryEvaluationHint;
import org.eclipse.viatra.query.runtime.matchers.psystem.PBody;
import org.eclipse.viatra.query.runtime.matchers.psystem.PVariable;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicdeferred.Equality;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicdeferred.ExportedParameter;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicdeferred.Inequality;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicdeferred.NegativePatternCall;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicenumerables.BinaryTransitiveClosure;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicenumerables.PositivePatternCall;
import org.eclipse.viatra.query.runtime.matchers.psystem.basicenumerables.TypeConstraint;
import org.eclipse.viatra.query.runtime.matchers.psystem.queries.BasePQuery;
import org.eclipse.viatra.query.runtime.matchers.psystem.queries.PParameter;
import org.eclipse.viatra.query.runtime.matchers.psystem.queries.PVisibility;
import org.eclipse.viatra.query.runtime.matchers.tuple.Tuples;

import tools.refinery.store.query.building.DNFAnd;
import tools.refinery.store.query.building.DNFAtom;
import tools.refinery.store.query.building.DNFPredicate;
import tools.refinery.store.query.building.EquivalenceAtom;
import tools.refinery.store.query.building.PredicateAtom;
import tools.refinery.store.query.building.RelationAtom;
import tools.refinery.store.query.building.Variable;

public class DNF2PQuery {

	public static SimplePQuery translate(DNFPredicate predicate, Map<DNFPredicate, SimplePQuery> dnf2PQueryMap) {
		SimplePQuery query = dnf2PQueryMap.get(predicate);
		if (query != null) {
			return query;
		}
		query = new DNF2PQuery().new SimplePQuery(predicate.getName());
		Map<Variable, PParameter> parameters = new HashMap<>();

		predicate.getVariables().forEach(variable -> parameters.put(variable, new PParameter(variable.getName())));
		List<PParameter> parameterList = new ArrayList<>();
		for(var param : predicate.getVariables()) {
			parameterList.add(parameters.get(param));
		}
		query.setParameter(parameterList);
		for (DNFAnd clause : predicate.getClauses()) {
			PBody body = new PBody(query);
			List<ExportedParameter> symbolicParameters = new ArrayList<>();
			for(var param : predicate.getVariables()) {
				PVariable pVar = body.getOrCreateVariableByName(param.getName());
				symbolicParameters.add(new ExportedParameter(body, pVar, parameters.get(param)));
			}
			body.setSymbolicParameters(symbolicParameters);
			query.addBody(body);
			for (DNFAtom constraint : clause.getConstraints()) {
				translateDNFAtom(constraint, body, dnf2PQueryMap);
			}
		}
		dnf2PQueryMap.put(predicate, query);
		return query;
	}

	private static void translateDNFAtom(DNFAtom constraint, PBody body, Map<DNFPredicate, SimplePQuery> dnf2PQueryMap) {
		if (constraint instanceof EquivalenceAtom equivalence) {
			translateEquivalenceAtom(equivalence, body);
		}
		if (constraint instanceof RelationAtom relation) {
			translateRelationAtom(relation, body);
		}
		if (constraint instanceof PredicateAtom predicate) {
			translatePredicateAtom(predicate, body, dnf2PQueryMap);
		}
	}

	private static void translateEquivalenceAtom(EquivalenceAtom equivalence, PBody body) {
		PVariable varSource = body.getOrCreateVariableByName(equivalence.getLeft().getName());
		PVariable varTarget = body.getOrCreateVariableByName(equivalence.getRight().getName());
		if (equivalence.isPositive())
			new Equality(body, varSource, varTarget);
		else
			new Inequality(body, varSource, varTarget);
	}

	private static void translateRelationAtom(RelationAtom relation, PBody body) {
		if (relation.getSubstitution().size() != relation.getView().getArity()) {
			throw new IllegalArgumentException("Arity (" + relation.getView().getArity()
					+ ") does not match parameter numbers (" + relation.getSubstitution().size() + ")");
		}
		Object[] variables = new Object[relation.getSubstitution().size()];
		for (int i = 0; i < relation.getSubstitution().size(); i++) {
			variables[i] = body.getOrCreateVariableByName(relation.getSubstitution().get(i).getName());
		}
		new TypeConstraint(body, Tuples.flatTupleOf(variables), relation.getView());
	}

	private static void translatePredicateAtom(PredicateAtom predicate, PBody body, Map<DNFPredicate, SimplePQuery> dnf2PQueryMap) {
		Object[] variables = new Object[predicate.getSubstitution().size()];
		for (int i = 0; i < predicate.getSubstitution().size(); i++) {
			variables[i] = body.getOrCreateVariableByName(predicate.getSubstitution().get(i).getName());
		}
		if (predicate.isPositive()) {
			if (predicate.isTransitive()) {
				if (predicate.getSubstitution().size() != 2) {
					throw new IllegalArgumentException("Transitive Predicate Atoms must be binary.");
				}
				new BinaryTransitiveClosure(body, Tuples.flatTupleOf(variables),
						DNF2PQuery.translate(predicate.getReferred(), dnf2PQueryMap));
			} else {
				new PositivePatternCall(body, Tuples.flatTupleOf(variables),
						DNF2PQuery.translate(predicate.getReferred(), dnf2PQueryMap));
			}
		} else {
			if (predicate.isTransitive()) {
				throw new InputMismatchException("Transitive Predicate Atoms cannot be negative.");
			} else {
				new NegativePatternCall(body, Tuples.flatTupleOf(variables),
						DNF2PQuery.translate(predicate.getReferred(), dnf2PQueryMap));
			}
		}
	}

	public class SimplePQuery extends BasePQuery {

		private String fullyQualifiedName;
		private List<PParameter> parameters;
		private LinkedHashSet<PBody> bodies = new LinkedHashSet<>();

		public SimplePQuery(String name) {
			super(PVisibility.PUBLIC);
			fullyQualifiedName = name;
		}

		@Override
		public String getFullyQualifiedName() {
			return fullyQualifiedName;
		}

		public void setParameter(List<PParameter> parameters) {
			this.parameters = parameters;
		}

		@Override
		public List<PParameter> getParameters() {
			return parameters;
		}

		public void addBody(PBody body) {
			bodies.add(body);
		}

		@Override
		protected Set<PBody> doGetContainedBodies() {
			setEvaluationHints(new QueryEvaluationHint(null, QueryEvaluationHint.BackendRequirement.UNSPECIFIED));
			return bodies;
		}

		public GenericQuerySpecification<RawPatternMatcher> build() {
			return new GenericQuerySpecification<RawPatternMatcher>(this) {

				@Override
				public Class<? extends QueryScope> getPreferredScopeClass() {
					return RelationalScope.class;
				}

				@Override
				protected RawPatternMatcher instantiate(ViatraQueryEngine engine) {
					RawPatternMatcher matcher = engine.getExistingMatcher(this);
			        if (matcher == null) {
			            matcher = engine.getMatcher(this);
			        } 	
			        return matcher;
				}

				@Override
				public RawPatternMatcher instantiate() {
					return new RawPatternMatcher(this);
				}

			};
		}
	}
}