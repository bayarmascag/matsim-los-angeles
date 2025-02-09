/* *********************************************************************** *
 * project: org.matsim.*
 * CharyparNagelOpenTimesScoringFunctionFactory.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.run;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.PtConstants;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a re-implementation of the original CharyparNagel function, based on a
 * modular approach.
 * @see <a href="http://www.matsim.org/node/263">http://www.matsim.org/node/263</a>
 * @author rashid_waraich
 */
public class CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney implements org.matsim.core.scoring.SumScoringFunction.LegScoring, org.matsim.core.scoring.SumScoringFunction.ArbitraryEventScoring {
	// yyyy URL in above javadoc is broken.  kai, feb'17

	private static final Logger log = Logger.getLogger( CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney.class ) ;

	protected double score;

	/** The parameters used for scoring */
	protected final ScoringParameters params;
	protected Network network;
	private boolean nextEnterVehicleIsFirstOfTrip = true;
	private boolean nextStartPtLegIsFirstOfTrip = true;
	private boolean currentLegIsPtLeg = false;
	private double lastActivityEndTime = Time.getUndefinedTime();
	private final Set<String> ptModes;
	private final Set<String> modesAlreadyConsideredForDailyConstants = new HashSet<>();
	private final double marginalUtilityOfMoney;

	public CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney(final ScoringParameters params, Network network, Set<String> ptModes) {
		this(params.marginalUtilityOfMoney, params, network, ptModes);
	}
	
	public CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney(final double marginalUtilityOfMoney, final ScoringParameters params, Network network, Set<String> ptModes) {
		this.params = params;
		this.network = network;
		this.ptModes = ptModes;
		this.marginalUtilityOfMoney = marginalUtilityOfMoney;
	}

	/**
	 * Scoring with pt modes set to 'pt'
	 */
	public CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney(final ScoringParameters params, Network network) {
		this(params, network, new HashSet<>(Collections.singletonList("pt")));
	}
	
	/**
	 * Scoring with pt modes set to 'pt'
	 */
	public CharyparNagelLegScoringWithPersonSpecificMarginalUtilityOfMoney(final double marginalUtilityOfMoney, final ScoringParameters params, Network network) {
		this(marginalUtilityOfMoney, params, network, new HashSet<>(Collections.singletonList("pt")));
	}


	@Override
	public void finish() {

	}

	@Override
	public double getScore() {
		return this.score;
	}

	private static int ccc=0 ;
	
	protected double calcLegScore(final double departureTime, final double arrivalTime, final Leg leg) {
		double tmpScore = 0.0;
		double travelTime = arrivalTime - departureTime; // travel time in seconds	
		ModeUtilityParameters modeParams = this.params.modeParams.get(leg.getMode());
		if (modeParams == null) {
			if (leg.getMode().equals(TransportMode.transit_walk) || leg.getMode().equals(TransportMode.non_network_walk )) {
				modeParams = this.params.modeParams.get(TransportMode.walk);
			} else {
				throw new RuntimeException("just encountered mode for which no scoring parameters are defined: " + leg.getMode()) ;
			}
		}
		tmpScore += travelTime * modeParams.marginalUtilityOfTraveling_s;
		if (modeParams.marginalUtilityOfDistance_m != 0.0
				|| modeParams.monetaryDistanceCostRate != 0.0) {
			Route route = leg.getRoute();
			double dist = route.getDistance(); // distance in meters
			if ( Double.isNaN(dist) ) {
				if ( ccc<10 ) {
					ccc++ ;
					Logger.getLogger(this.getClass()).warn("distance is NaN. Will make score of this plan NaN. Possible reason: Simulation does not report " +
							"a distance for this trip. Possible reason for that: mode is teleported and router does not " +
							"write distance into plan.  Needs to be fixed or these plans will die out.") ;
					if ( ccc==10 ) {
						Logger.getLogger(this.getClass()).warn(Gbl.FUTURE_SUPPRESSED) ;
					}
				}
			}
			tmpScore += modeParams.marginalUtilityOfDistance_m * dist;
			tmpScore += modeParams.monetaryDistanceCostRate * this.marginalUtilityOfMoney * dist;
		}
		tmpScore += modeParams.constant;
		// (yyyy once we have multiple legs without "real" activities in between, this will produce wrong results.  kai, dec'12)
		// (yy NOTE: the constant is added for _every_ pt leg.  This is not how such models are estimated.  kai, nov'12)
		
		// account for the daily constants
		if (!modesAlreadyConsideredForDailyConstants.contains(leg.getMode())) {
			tmpScore += modeParams.dailyUtilityConstant + modeParams.dailyMoneyConstant * this.marginalUtilityOfMoney;
			modesAlreadyConsideredForDailyConstants.add(leg.getMode());
		}
		// yyyy the above will cause problems if we ever decide to differentiate pt mode into bus, tram, train, ...
		// Might have to move the MainModeIdentifier then.  kai, sep'18
		
		return tmpScore;
	}
	
	@Override
	public void handleEvent(Event event) {
		if ( event instanceof ActivityEndEvent ) {
			// When there is a "real" activity, flags are reset:
			if ( !PtConstants.TRANSIT_ACTIVITY_TYPE.equals( ((ActivityEndEvent)event).getActType()) ) {
				this.nextEnterVehicleIsFirstOfTrip  = true ;
				this.nextStartPtLegIsFirstOfTrip = true ;
			}
			this.lastActivityEndTime = event.getTime() ;
		}

		if ( event instanceof PersonEntersVehicleEvent && currentLegIsPtLeg ) {
			if ( !this.nextEnterVehicleIsFirstOfTrip ) {
				// all vehicle entering after the first triggers the disutility of line switch:
				this.score  += params.utilityOfLineSwitch ;
			}
			this.nextEnterVehicleIsFirstOfTrip = false ;
			// add score of waiting, _minus_ score of travelling (since it is added in the legscoring above):
			this.score += (event.getTime() - this.lastActivityEndTime) * (this.params.marginalUtilityOfWaitingPt_s - this.params.modeParams.get(TransportMode.pt).marginalUtilityOfTraveling_s) ;
		}

		if ( event instanceof PersonDepartureEvent ) {
			String mode = ((PersonDepartureEvent)event).getLegMode();
			
			this.currentLegIsPtLeg = this.ptModes.contains(mode);
			if ( currentLegIsPtLeg ) {
				if ( !this.nextStartPtLegIsFirstOfTrip ) {
					this.score -= params.modeParams.get(mode).constant ;
					// (yyyy deducting this again, since is it wrongly added above.  should be consolidated; this is so the code
					// modification is minimally invasive.  kai, dec'12)
				}
				this.nextStartPtLegIsFirstOfTrip = false ;
			}
		}
	}

	@Override
	public void handleLeg(Leg leg) {
		Gbl.assertIf( !Time.isUndefinedTime( leg.getDepartureTime() ) ) ;
		Gbl.assertIf( !Time.isUndefinedTime( leg.getTravelTime() ) );

		double legScore = calcLegScore(leg.getDepartureTime(), leg.getDepartureTime() + leg.getTravelTime(), leg);
		if ( Double.isNaN( legScore )) {
			log.error( "dpTime=" + leg.getDepartureTime() + "; ttime=" + leg.getTravelTime() + "; leg=" + leg ) ;
			throw new RuntimeException("score is NaN") ;
		}
		this.score += legScore;
	}
}
