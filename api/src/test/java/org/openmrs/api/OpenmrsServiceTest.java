/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.api;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import junit.framework.Assert;

import org.junit.Test;
import org.openmrs.Cohort;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseContextSensitiveTest;

/**
 * Contains methods to test behavior of OpenmrsService methods
 */
public class OpenmrsServiceTest extends BaseContextSensitiveTest {
	
	/**
	 * Tests that if two service methods are called (one from inside the other) the first one will
	 * not be rolled back if there is an error during the second one.
	 * 
	 * <pre>
	 * We are testing with the merge patient method since it is transactional and calls multiple other 
	 * transactional methods
	 * </pre>
	 */
	@Test
	public void shouldCheckThatAMethodIsNotRolledBackInCaseOfAnErrorInAnotherInvokedInsideIt() throws Exception {
		PatientService patientService = Context.getPatientService();
		EncounterService encounterService = Context.getEncounterService();
		ProgramWorkflowService programService = Context.getProgramWorkflowService();
		Patient prefPatient = patientService.getPatient(6);
		Patient notPrefPatient = patientService.getPatient(7);
		Collection<Program> programs = programService.getAllPrograms(false);
		
		int originalPrefEncounterCount = encounterService.getEncountersByPatient(prefPatient).size();
		int originalNotPrefEncounterCount = encounterService.getEncountersByPatient(notPrefPatient).size();
		Assert.assertTrue(originalNotPrefEncounterCount > 0);
		
		//Add another program to the not preferred patient for testing purposes
		PatientProgram pp = new PatientProgram();
		pp.setPatient(notPrefPatient);
		pp.setProgram(programs.iterator().next());
		pp.setDateEnrolled(new Date());
		pp.setCreator(Context.getAuthenticatedUser());
		programService.savePatientProgram(pp);
		
		Cohort preferredCohort = new Cohort(prefPatient.getPatientId().toString());
		int originalPrefProgramCount = programService.getPatientPrograms(preferredCohort, programs).size();
		Cohort notPreferredCohort = new Cohort(notPrefPatient.getPatientId().toString());
		List<PatientProgram> notPrefPrograms = programService.getPatientPrograms(notPreferredCohort, programs);
		int originalNotPrefProgramCount = notPrefPrograms.size();
		//we should have atleast 2 patient programs for a concrete test
		Assert.assertTrue(originalNotPrefProgramCount > 1);
		
		//Set the program to null so that the second patient program is rejected on validation with
		//an APIException, since it is a RuntimeException, hibernate should technically trigger 
		//a roll back when programService.savePatientProgram is called from the patient merge method
		//for this patient program
		notPrefPrograms.get(1).setProgram(null);
		
		boolean failed = false;
		try {
			patientService.mergePatients(prefPatient, notPrefPatient);
		}
		catch (APIException e) {
			failed = true;//should have failed to force a rollback
		}
		Assert.assertTrue(failed);
		
		//Since the encounters are moved first, that logic shouldn't have been rolled back
		Assert.assertEquals(originalPrefEncounterCount + originalNotPrefEncounterCount, encounterService
		        .getEncountersByPatient(prefPatient).size());
		
		//The first valid patient program should not have been rolled back, so only the second should have been rolled back
		Assert.assertEquals(originalPrefProgramCount + originalNotPrefProgramCount - 1, programService.getPatientPrograms(
		    preferredCohort, programs).size());
	}
}
