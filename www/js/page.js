// Base for every screen: Ionic pages are plain custom elements with the .ion-page class.
// build() runs once; subscribe() returns unsubscribers that live while the page is attached.
export class Page extends HTMLElement {
  connectedCallback() {
    this.classList.add('ion-page');
    if (!this._built) { this._built = true; this.build(); }
    this._subs = this.subscribe?.() || [];
    this.attached?.();
  }
  disconnectedCallback() {
    this._subs?.forEach((off) => off());
    this._subs = [];
    this.detached?.();
  }
}
export const nav = () => document.getElementById('nav');
export const push = (tag, props) => nav().push(tag, props);
