import * as functions from "firebase-functions";

/**
 * Structured logger for Cloud Functions to enable end-to-end tracing.
 */
export const logger = {
  info: (message: string, context?: any) => {
    functions.logger.info(message, context);
  },
  warn: (message: string, context?: any) => {
    functions.logger.warn(message, context);
  },
  error: (message: string, context?: any) => {
    functions.logger.error(message, context);
  },
  /**
   * Logs an interaction event with correlation IDs.
   */
  interaction: (event: string, interactionData: any, status: 'SUCCESS' | 'FAILURE' | 'PROCESSING', details?: any) => {
    functions.logger.info(`INTERACTION_EVENT: ${event}`, {
      correlationId: interactionData.correlationId || 'unknown',
      artifactId: interactionData.artifactId,
      userId: interactionData.userId,
      status,
      ...details
    });
  }
};
