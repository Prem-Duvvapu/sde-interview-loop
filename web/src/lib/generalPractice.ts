import type { CompanyProfile } from '../api/types';

export const GENERAL_PRACTICE_ID = 'general-practice';
export const GENERAL_PRACTICE_LABEL = 'General SDE-2 practice';

/** A client-only display entry; it is intentionally not a calibrated company profile. */
export const GENERAL_PRACTICE_PROFILE: CompanyProfile = {
  id: GENERAL_PRACTICE_ID,
  displayName: GENERAL_PRACTICE_LABEL,
};

export function isGeneralPractice(id: string | null | undefined): boolean {
  return id === GENERAL_PRACTICE_ID;
}
