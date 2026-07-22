declare module "passport-microsoft" {
  import { Strategy as PassportStrategy } from "passport";

  export interface Profile {
    id: string;
    displayName: string;
    emails?: { value: string }[];
    photos?: { value: string }[];
  }

  export interface StrategyOptions {
    clientID: string;
    clientSecret: string;
    callbackURL: string;
    scope?: string[];
    tenant?: string;
  }

  export type VerifyCallback = (error: unknown, user?: Express.User | false) => void;
  export type VerifyFunction = (
    accessToken: string,
    refreshToken: string,
    profile: Profile,
    done: VerifyCallback
  ) => void;

  export class Strategy extends PassportStrategy {
    constructor(options: StrategyOptions, verify: VerifyFunction);
  }
}
